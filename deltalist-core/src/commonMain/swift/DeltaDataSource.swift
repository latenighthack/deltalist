#if canImport(UIKit)
import UIKit
import Combine

/// Generic UICollectionView data source that observes a DeltaList (Kotlin Flow<Delta<T>>).
/// Equivalent to Android's DeltaAdapter. Supports both regular lists and soft/paginated lists.
///
/// Uses direct UICollectionViewDataSource implementation with performBatchUpdates for efficient updates.
///
/// CRITICAL: Never access delta.items directly! It triggers NSArray bridging which iterates the
/// ENTIRE list - catastrophic for soft lists. Always use delta.loadedItems() and delta.totalSize().
///
/// For soft/paginated lists, provide a `loadingCellProvider` to display loading placeholders.
/// The data source will automatically trigger loads when unloaded items become visible.
@available(iOS 14.0, *)
@MainActor
public class DeltaCollectionDataSource<T: AnyObject>: NSObject,
    UICollectionViewDataSource,
    UICollectionViewDelegate
{
    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell
    public typealias LoadingCellProvider = (UICollectionView, IndexPath) -> UICollectionViewCell
    public typealias SupplementaryViewProvider = (UICollectionView, String, IndexPath) -> UICollectionReusableView?

    // MARK: - Properties

    weak var collectionView: UICollectionView?
    private(set) public var items: [T] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    // Soft list support - store the current delta for pagination methods
    private var currentDelta: AnyObject?

    private let cellProvider: CellProvider
    private let loadingCellProvider: LoadingCellProvider?
    private var supplementaryViewProvider: SupplementaryViewProvider?

    /// The total size of the list (including unloaded items for soft lists).
    private(set) public var totalSize: Int = 0

    /// The item count the collection view currently reflects. Stepped one mutation at a
    /// time while a Change.Mutations is applied so that each per-operation batch update
    /// stays internally consistent (running-index semantics). Equals totalSize at rest.
    private var appliedCount: Int = 0

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback invoked when the bound delta stream terminates with an error. Defaults to
    /// nil (silent). Wire this to your telemetry/logging so upstream failures are visible
    /// instead of looking identical to normal completion.
    public var onError: ((Error) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider,
        loadingCellProvider: LoadingCellProvider? = nil
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        self.loadingCellProvider = loadingCellProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    /// Starts collecting deltas from a typed stream and applying them to the collection view.
    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    guard let self = self else { break }
                    self.applyDelta(delta)
                }
            } catch {
                // Surface the failure if a handler is set; otherwise complete silently.
                self?.onError?(error)
            }
        }
    }

    /// Starts collecting deltas from an erased stream (for type-erased Kotlin flows).
    public func bind(erased stream: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        self.applyDeltaErased(delta)
                    } else {
                        // Cross-module compatibility
                        self.applyDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                // Surface the failure if a handler is set; otherwise complete silently.
                self?.onError?(error)
            }
        }
    }

    /// Manually apply a delta value. Use this when you need to handle flow collection yourself
    /// (e.g., for protocol types like MoveableDeltaList that don't get AsyncSequence conformance).
    public func apply(delta: Any) {
        if let typedDelta = delta as? Delta<T> {
            applyDelta(typedDelta)
        } else if let anyDelta = delta as? Delta<AnyObject> {
            applyDeltaErased(anyDelta)
        } else {
            applyDeltaAny(delta as AnyObject)
        }
    }

    /// Stops collecting deltas.
    public func unbind() {
        task?.cancel()
        task = nil
    }

    /// Sets the binding task for external collectors (e.g., MoveableDeltaList extensions).
    public func setBindingTask(_ newTask: Task<Void, Never>) {
        task = newTask
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        currentDelta = delta

        // Use loadedItems() to safely get only loaded items without triggering bridging
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }

        // Use totalSize() for soft lists
        totalSize = Int(delta.totalSize())

        onItemsChanged?(items)
        applyChange(delta.change)

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        currentDelta = delta

        // Use loadedItems() to safely get only loaded items without triggering bridging
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }

        // Use totalSize() for soft lists
        totalSize = Int(delta.totalSize())

        onItemsChanged?(items)
        applyChange(delta.change)

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    /// Apply delta from any type (cross-module compatibility)
    private func applyDeltaAny(_ delta: AnyObject) {
        currentDelta = delta

        // NEVER access "items" property - it triggers the bridging catastrophe!
        // Use loadedItems() method instead
        var extractedItems: [T] = []

        // Try calling loadedItems() method via protocol
        if let loadedArray = (delta as? DeltaProtocol)?.loadedItems() as? [AnyObject] {
            extractedItems = loadedArray.compactMap { $0 as? T }
        }

        items = extractedItems

        // Get totalSize for soft lists
        if let deltaProto = delta as? DeltaProtocol {
            totalSize = Int(deltaProto.totalSize())
        } else {
            totalSize = items.count
        }

        onItemsChanged?(items)

        // Get change and apply
        if let deltaProto = delta as? DeltaProtocol {
            applyChange(deltaProto.change)
        } else {
            // On first data or unknown, always reload
            if !hasReceivedInitialData {
                hasReceivedInitialData = true
            }
            appliedCount = totalSize
            collectionView?.reloadData()
        }

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    private func applyChange(_ change: Change) {
        guard let collectionView = collectionView else { return }

        // On first data, always reload to sync collection view state.
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            appliedCount = totalSize
            collectionView.reloadData()
            return
        }

        if change is Change.Reload {
            appliedCount = totalSize
            collectionView.reloadData()
            return
        }

        guard let mutations = change as? Change.Mutations else {
            // Unknown change type: rebuild safely.
            appliedCount = totalSize
            collectionView.reloadData()
            return
        }

        // The operations use running-index (sequential) coordinates. Replaying them in a
        // single performBatchUpdates would misinterpret them as simultaneous before/after
        // coordinates and crash (NSInternalInconsistencyException) whenever a Move is mixed
        // with structural ops. Instead apply each operation in its own batch, stepping
        // appliedCount so numberOfItemsInSection stays consistent at every step.
        //
        // If the stream is inconsistent with the snapshot's size change (e.g. an upstream
        // desync), fall back to a full reload rather than crash.
        guard mutationsAreConsistent(mutations.operations, startCount: appliedCount, endCount: totalSize) else {
            appliedCount = totalSize
            collectionView.reloadData()
            return
        }

        for operation in mutations.operations {
            collectionView.performBatchUpdates {
                if let insert = operation as? Mutation.Insert {
                    let indexPaths = (0..<Int(insert.count)).map {
                        IndexPath(item: Int(insert.index) + $0, section: 0)
                    }
                    appliedCount += Int(insert.count)
                    collectionView.insertItems(at: indexPaths)
                } else if let remove = operation as? Mutation.Remove {
                    let indexPaths = (0..<Int(remove.count)).map {
                        IndexPath(item: Int(remove.index) + $0, section: 0)
                    }
                    appliedCount -= Int(remove.count)
                    collectionView.deleteItems(at: indexPaths)
                } else if let update = operation as? Mutation.Update {
                    let indexPaths = (0..<Int(update.count)).map {
                        IndexPath(item: Int(update.index) + $0, section: 0)
                    }
                    collectionView.reloadItems(at: indexPaths)
                } else if let move = operation as? Mutation.Move {
                    // Single-item move per the Change contract; count is unchanged.
                    let from = IndexPath(item: Int(move.fromIndex), section: 0)
                    let to = IndexPath(item: Int(move.toIndex), section: 0)
                    collectionView.moveItem(at: from, to: to)
                }
            }
        }
    }

    /// Simulates the running item count through `operations`, bounds-checking each step.
    /// Returns true only if every operation is in range and the final count equals
    /// `endCount` (mirrors the Android adapter's guard).
    private func mutationsAreConsistent(_ operations: [Mutation], startCount: Int, endCount: Int) -> Bool {
        var count = startCount
        for operation in operations {
            if let insert = operation as? Mutation.Insert {
                let c = Int(insert.count), i = Int(insert.index)
                if c < 0 || i < 0 || i > count { return false }
                count += c
            } else if let remove = operation as? Mutation.Remove {
                let c = Int(remove.count), i = Int(remove.index)
                if c < 0 || i < 0 || i + c > count { return false }
                count -= c
            } else if let update = operation as? Mutation.Update {
                let c = Int(update.count), i = Int(update.index)
                if c < 0 || i < 0 || i + c > count { return false }
            } else if let move = operation as? Mutation.Move {
                if Int(move.count) != 1 { return false }
                let f = Int(move.fromIndex), t = Int(move.toIndex)
                if f < 0 || f >= count || t < 0 || t >= count { return false }
            } else {
                return false
            }
        }
        return count == endCount
    }

    // MARK: - Soft List Support

    /// Returns true if the item at the given index is loaded (for soft lists).
    public func isLoadedAt(index: Int) -> Bool {
        if let delta = currentDelta as? Delta<T> {
            return delta.isLoadedAt(index: Int32(index))
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.isLoadedAt(index: Int32(index))
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            return deltaProto.isLoadedAt(index: Int32(index))
        }
        return index < items.count
    }

    /// Returns the loaded item at the given index, or nil if not loaded (for soft lists).
    public func getLoadedItemAt(index: Int) -> T? {
        if let delta = currentDelta as? Delta<T> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            return deltaProto.getLoadedItemAt(index: Int32(index)) as? T
        }
        return index < items.count ? items[index] : nil
    }

    /// Triggers loading at the given index (for soft lists).
    public func triggerLoadAt(index: Int) {
        if let delta = currentDelta as? Delta<T> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            deltaProto.triggerLoadAt(index: Int32(index))
            return
        }
    }

    /// Triggers loading for all visible cells that are not yet loaded.
    private func triggerLoadsForVisibleCells() {
        guard loadingCellProvider != nil else { return }
        guard let collectionView = collectionView else { return }

        for indexPath in collectionView.indexPathsForVisibleItems {
            let index = indexPath.item
            if !isLoadedAt(index: index) {
                triggerLoadAt(index: index)
            }
        }
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        return 1
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        // Reflects intermediate state while a Change.Mutations is being applied; equals
        // totalSize at rest.
        return appliedCount
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let index = indexPath.item

        if let item = getLoadedItemAt(index: index) {
            return cellProvider(collectionView, indexPath, item)
        } else if let loadingProvider = loadingCellProvider {
            return loadingProvider(collectionView, indexPath)
        } else if index < items.count {
            return cellProvider(collectionView, indexPath, items[index])
        } else {
            // Never fatalError in a cell provider: a transient inconsistency during an
            // animated update must be recoverable, not a hard crash. Return an empty
            // placeholder; the next consistent snapshot will reconcile.
            return loadingCellProvider?(collectionView, indexPath) ?? UICollectionViewCell()
        }
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        return supplementaryViewProvider?(collectionView, kind, indexPath) ?? UICollectionReusableView()
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        let index = indexPath.item
        if !isLoadedAt(index: index) {
            triggerLoadAt(index: index)
        }
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override to handle lazy item release
    }

    public func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if let item = getLoadedItemAt(index: indexPath.item) {
            onItemSelected?(indexPath, item)
        }
        collectionView.deselectItem(at: indexPath, animated: true)
    }

    // MARK: - Supplementary Views

    public func setSupplementaryViewProvider(_ provider: SupplementaryViewProvider?) {
        self.supplementaryViewProvider = provider
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        return getLoadedItemAt(index: indexPath.item)
    }

    public func item(at index: Int) -> T? {
        return getLoadedItemAt(index: index)
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Sectioned Delta Collection Data Source

/// Traditional UICollectionViewDataSource that observes a SectionedDeltaList.
/// NO DiffableDataSource - direct data source/delegate implementation with performBatchUpdates.
@available(iOS 14.0, *)
@MainActor
public class SectionedDeltaCollectionDataSource<H: AnyObject, T: AnyObject>: NSObject,
    UICollectionViewDataSource,
    UICollectionViewDelegate
{
    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell
    public typealias HeaderProvider = (UICollectionView, IndexPath, H) -> UICollectionReusableView

    // MARK: - Section Data

    public struct SectionData {
        public let header: H
        public var items: [T]

        public init(header: H, items: [T]) {
            self.header = header
            self.items = items
        }
    }

    // MARK: - Properties

    weak var collectionView: UICollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    // Stepped counts so per-operation batch updates stay consistent during application
    // (mirrors DeltaCollectionDataSource). At rest, appliedSectionCount == sections.count
    // and applyingSection == -1.
    private var appliedSectionCount: Int = 0
    private var applyingSection: Int = -1
    private var applyingItemCount: Int = 0

    private let cellProvider: CellProvider
    private let headerProvider: HeaderProvider?

    /// Callback when sections are updated.
    public var onSectionsChanged: (([SectionData]) -> Void)?

    /// Callback invoked when the bound delta stream terminates with an error. Defaults to
    /// nil (silent). Wire this to telemetry to make upstream failures visible.
    public var onError: ((Error) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback when a header is tapped.
    public var onHeaderSelected: ((Int, H) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider,
        headerProvider: HeaderProvider? = nil
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        self.headerProvider = headerProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    public func bind(to flow: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    if let sectionedDelta = value as? SectionedDelta<H, T> {
                        self.applySectionedDelta(sectionedDelta)
                    } else if let sectionedDelta = value as? SectionedDelta<AnyObject, AnyObject> {
                        self.applySectionedDeltaErased(sectionedDelta)
                    } else {
                        // Cross-module compatibility
                        self.applySectionedDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                self?.onError?(error)
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application
    // CRITICAL: Never access delta.sections directly! Use Kotlin helper methods to avoid bridging.

    private func applySectionedDelta(_ delta: SectionedDelta<H, T>) {
        // Use Kotlin helper methods to avoid bridging catastrophe
        let sectionCount = Int(delta.sectionCount())
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            // Get items one by one to avoid list bridging
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaErased(_ delta: SectionedDelta<AnyObject, AnyObject>) {
        // Use Kotlin helper methods to avoid bridging catastrophe
        let sectionCount = Int(delta.sectionCount())
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            // Get items one by one to avoid list bridging
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: use Obj-C runtime to call methods directly
        // This handles cases like DemoCoreSectionedDelta where generic casts fail

        // Use Obj-C runtime directly - don't use KVC as it doesn't work with Kotlin classes
        let sectionCount = callSectionCountViaRuntime(delta)

        if sectionCount == 0 {
            // Check if we got 0 because there are no sections, or because the call failed.
            // Try one more cast approach.
            if let anyDelta = delta as? SectionedDelta<AnyObject, AnyObject> {
                let count = Int(anyDelta.sectionCount())
                if count > 0 {
                    extractAndApplySections(from: anyDelta, sectionCount: count)
                    return
                }
            }
            sections = []
            appliedSectionCount = 0
            onSectionsChanged?([])
            collectionView?.reloadData()
            return
        }

        // Extract sections using Obj-C runtime calls
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = callGetHeaderAt(delta, sectionIndex: Int32(sectionIdx)) as? H else {
                continue
            }

            let itemCount = callGetItemCountAt(delta, sectionIndex: Int32(sectionIdx))

            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = callGetItemAt(delta, sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        // Get change via Obj-C runtime
        let change = callGetChange(delta)

        // On first data, always reload
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            sections = newSections
            appliedSectionCount = newSections.count
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
            return
        }

        let oldSectionCount = sections.count
        let oldItemCounts = sections.map { $0.items.count }
        sections = newSections
        onSectionsChanged?(newSections)

        if let change = change {
            applySectionedChange(change, oldSectionCount: oldSectionCount, oldItemCounts: oldItemCounts)
        } else {
            appliedSectionCount = newSections.count
            collectionView?.reloadData()
        }
    }

    private func extractAndApplySections(from delta: SectionedDelta<AnyObject, AnyObject>, sectionCount: Int) {
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    /// Call sectionCount via ObjC runtime for Int32 return type
    private func callSectionCountViaRuntime(_ obj: AnyObject) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> Int32
        let sel = DeltaSelector.sectionCount
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
        return Int(unsafeBitCast(imp, to: MethodType.self)(obj, sel))
    }

    /// Call change property via ObjC runtime
    private func callGetChange(_ obj: AnyObject) -> SectionedChange? {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> AnyObject?
        let sel = DeltaSelector.change
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel) as? SectionedChange
    }

    /// Call getHeaderAt via ObjC runtime
    private func callGetHeaderAt(_ obj: AnyObject, sectionIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
        let sel = DeltaSelector.getHeaderAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex)
    }

    /// Call getItemCountAt via ObjC runtime
    private func callGetItemCountAt(_ obj: AnyObject, sectionIndex: Int32) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> Int32
        let sel = DeltaSelector.getItemCountAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
        return Int(unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex))
    }

    /// Call getItemAt via ObjC runtime
    private func callGetItemAt(_ obj: AnyObject, sectionIndex: Int32, itemIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32, Int32) -> AnyObject?
        let sel = DeltaSelector.getItemAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex, itemIndex)
    }

    private func applyChanges(newSections: [SectionData], change: SectionedChange) {
        // Capture pre-change counts before overwriting `sections` so the per-operation
        // stepping below can report consistent intermediate counts.
        let oldSectionCount = sections.count
        let oldItemCounts = sections.map { $0.items.count }

        sections = newSections
        onSectionsChanged?(newSections)

        guard let collectionView = collectionView else { return }

        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            appliedSectionCount = sections.count
            collectionView.reloadData()
            return
        }

        applySectionedChange(change, oldSectionCount: oldSectionCount, oldItemCounts: oldItemCounts)
    }

    private func applySectionedChange(_ change: SectionedChange, oldSectionCount: Int, oldItemCounts: [Int]) {
        guard let collectionView = collectionView else { return }

        if change is SectionedChange.Reload {
            appliedSectionCount = sections.count
            collectionView.reloadData()
            return
        }

        if let sectionChanges = change as? SectionedChange.Sections {
            // Section-level mutations in running coordinates: apply one per batch, stepping
            // appliedSectionCount (which numberOfSections returns during application).
            appliedSectionCount = oldSectionCount
            for mutation in sectionChanges.mutations {
                collectionView.performBatchUpdates {
                    if let insert = mutation as? SectionMutation.Insert {
                        let c = Int(insert.count)
                        appliedSectionCount += c
                        collectionView.insertSections(IndexSet(Int(insert.index)..<Int(insert.index) + c))
                    } else if let remove = mutation as? SectionMutation.Remove {
                        let c = Int(remove.count)
                        appliedSectionCount -= c
                        collectionView.deleteSections(IndexSet(Int(remove.index)..<Int(remove.index) + c))
                    } else if let update = mutation as? SectionMutation.Update {
                        collectionView.reloadSections(IndexSet(integer: Int(update.index)))
                    } else if let move = mutation as? SectionMutation.Move {
                        collectionView.moveSection(Int(move.fromIndex), toSection: Int(move.toIndex))
                    }
                }
            }
            appliedSectionCount = sections.count
            return
        }

        if let itemChanges = change as? SectionedChange.Items {
            let sectionIndex = Int(itemChanges.section)
            guard sectionIndex >= 0, sectionIndex < sections.count, sectionIndex < oldItemCounts.count else {
                appliedSectionCount = sections.count
                collectionView.reloadData()
                return
            }
            let startCount = oldItemCounts[sectionIndex]
            let endCount = sections[sectionIndex].items.count
            guard mutationsAreConsistent(itemChanges.mutations, startCount: startCount, endCount: endCount) else {
                collectionView.reloadSections(IndexSet(integer: sectionIndex))
                return
            }

            applyingSection = sectionIndex
            applyingItemCount = startCount
            for mutation in itemChanges.mutations {
                collectionView.performBatchUpdates {
                    if let insert = mutation as? Mutation.Insert {
                        let c = Int(insert.count)
                        let indexPaths = (0..<c).map { IndexPath(item: Int(insert.index) + $0, section: sectionIndex) }
                        applyingItemCount += c
                        collectionView.insertItems(at: indexPaths)
                    } else if let remove = mutation as? Mutation.Remove {
                        let c = Int(remove.count)
                        let indexPaths = (0..<c).map { IndexPath(item: Int(remove.index) + $0, section: sectionIndex) }
                        applyingItemCount -= c
                        collectionView.deleteItems(at: indexPaths)
                    } else if let update = mutation as? Mutation.Update {
                        let indexPaths = (0..<Int(update.count)).map { IndexPath(item: Int(update.index) + $0, section: sectionIndex) }
                        collectionView.reloadItems(at: indexPaths)
                    } else if let move = mutation as? Mutation.Move {
                        let from = IndexPath(item: Int(move.fromIndex), section: sectionIndex)
                        let to = IndexPath(item: Int(move.toIndex), section: sectionIndex)
                        collectionView.moveItem(at: from, to: to)
                    }
                }
            }
            applyingSection = -1
            return
        }

        appliedSectionCount = sections.count
        collectionView.reloadData()
    }

    /// Simulates the running item count through `operations`, bounds-checking each step.
    private func mutationsAreConsistent(_ operations: [Mutation], startCount: Int, endCount: Int) -> Bool {
        var count = startCount
        for operation in operations {
            if let insert = operation as? Mutation.Insert {
                let c = Int(insert.count), i = Int(insert.index)
                if c < 0 || i < 0 || i > count { return false }
                count += c
            } else if let remove = operation as? Mutation.Remove {
                let c = Int(remove.count), i = Int(remove.index)
                if c < 0 || i < 0 || i + c > count { return false }
                count -= c
            } else if let update = operation as? Mutation.Update {
                let c = Int(update.count), i = Int(update.index)
                if c < 0 || i < 0 || i + c > count { return false }
            } else if let move = operation as? Mutation.Move {
                if Int(move.count) != 1 { return false }
                let f = Int(move.fromIndex), t = Int(move.toIndex)
                if f < 0 || f >= count || t < 0 || t >= count { return false }
            } else {
                return false
            }
        }
        return count == endCount
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        // Reflects intermediate state while section-level mutations are applied.
        return appliedSectionCount
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        // Reflects intermediate state for the section currently being mutated.
        if section == applyingSection { return applyingItemCount }
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            // Never fatalError in a cell provider: a transient inconsistency during an
            // animated update must be recoverable. Return an empty placeholder.
            return UICollectionViewCell()
        }
        let item = sections[indexPath.section].items[indexPath.item]
        return cellProvider(collectionView, indexPath, item)
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        guard kind == UICollectionView.elementKindSectionHeader,
              let headerProvider = headerProvider,
              indexPath.section < sections.count else {
            return UICollectionReusableView()
        }
        let header = sections[indexPath.section].header
        return headerProvider(collectionView, indexPath, header)
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return }
        let item = sections[indexPath.section].items[indexPath.item]
        onItemSelected?(indexPath, item)
        collectionView.deselectItem(at: indexPath, animated: true)
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return nil }
        return sections[indexPath.section].items[indexPath.item]
    }

    public func header(at section: Int) -> H? {
        guard section < sections.count else { return nil }
        return sections[section].header
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Stable Delta Collection Data Source

/// Data source that uses stable IDs from StableItem types directly.
/// More efficient for lists using the withStableIds() operator.
@available(iOS 14.0, *)
@MainActor
public class StableDeltaCollectionDataSource<T: AnyObject>: NSObject, UICollectionViewDelegate {

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, Int32>!
    private var items: [T] = []
    private var itemsByStableId: [Int32: T] = [:]
    private var task: Task<Void, Never>?

    private let cellProvider: CellProvider
    private let stableIdExtractor: (T) -> Int32

    public var currentItems: [T] { items }

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Invoked if the bound delta stream terminates with an error. Defaults to nil
    /// (silent); set it to route upstream failures to telemetry.
    public var onError: ((Error) -> Void)?

    public init(
        collectionView: UICollectionView,
        stableIdExtractor: @escaping (T) -> Int32,
        cellProvider: @escaping CellProvider
    ) {
        self.collectionView = collectionView
        self.stableIdExtractor = stableIdExtractor
        self.cellProvider = cellProvider
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: UICollectionView) {
        diffableDataSource = UICollectionViewDiffableDataSource<Int, Int32>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, stableId in
            guard let self = self,
                  let item = self.itemsByStableId[stableId] else { return nil }
            return self.cellProvider(collectionView, indexPath, item)
        }
    }

    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    self.applyDelta(delta)
                }
            } catch {
                self.onError?(error)
            }
        }
    }

    public func bind(erased stream: some AsyncSequence) {
        unbind()
        task = Task { @MainActor in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        self.applyDeltaErased(delta)
                    } else {
                        // Cross-module fallback: cast to base Delta type
                        self.applyDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                self.onError?(error)
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    private func applyDelta(_ delta: Delta<T>) {
        // Use loadedItems() to avoid bridging catastrophe
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(delta.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        // Use loadedItems() to avoid bridging catastrophe
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(delta.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    private func applyDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: access Delta properties directly
        // Delta is a Kotlin class, so we can call its methods
        guard let deltaBase = delta as? Delta<NSObject> else {
            // Last resort: a Delta vended by another SKIE framework — call loadedItems() by selector.
            typealias Fn = @convention(c) (AnyObject, Selector) -> NSArray?
            if let imp = DeltaIMPCache.shared.imp(for: delta, DeltaSelector.loadedItems),
               let loadedArray = unsafeBitCast(imp, to: Fn.self)(delta, DeltaSelector.loadedItems) as? [AnyObject] {
                items = loadedArray.compactMap { $0 as? T }
                itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
                onItemsChanged?(items)

                var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
                snapshot.appendSections([0])
                snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)
                diffableDataSource.apply(snapshot, animatingDifferences: false)
            }
            return
        }

        let loadedItems = deltaBase.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(deltaBase.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.item < items.count else { return nil }
        return items[indexPath.item]
    }

    public func item(at index: Int) -> T? {
        guard index < items.count else { return nil }
        return items[index]
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Protocol Interfaces for Cross-Module Compatibility
//
// `DeltaProtocol` and `SectionedDeltaProtocol` are defined in DeltaList.swift (outside this file's
// `#if canImport(UIKit)`) so both the UIKit data sources and the SwiftUI wrappers can share them.

/// Protocol for accessing Section properties.
/// WARNING: Accessing the `items` property triggers NSArray bridging.
@objc public protocol SectionProtocol {
    var header: Any? { get }
    var items: [Any] { get }
}
#endif

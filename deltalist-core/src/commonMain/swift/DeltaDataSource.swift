#if canImport(UIKit)
import UIKit
import Combine

/// Generic UICollectionView data source that observes a DeltaList (Kotlin Flow<Delta<T>>).
/// Equivalent to Android's DeltaAdapter. Supports both regular lists and soft/paginated lists.
///
/// Uses direct UICollectionViewDataSource implementation with performBatchUpdates for efficient updates.
///
/// For soft/paginated lists, provide a `loadingCellProvider` to display loading placeholders.
/// The data source will automatically trigger loads when unloaded items become visible.
///
/// Usage:
/// ```swift
/// // Regular list
/// let dataSource = DeltaCollectionDataSource<Item>(
///     collectionView: collectionView,
///     cellProvider: { collectionView, indexPath, item in
///         // return configured cell
///     }
/// )
///
/// // Soft/paginated list
/// let dataSource = DeltaCollectionDataSource<Item>(
///     collectionView: collectionView,
///     cellProvider: { collectionView, indexPath, item in
///         // return configured cell for loaded item
///     },
///     loadingCellProvider: { collectionView, indexPath in
///         // return loading placeholder cell
///     }
/// )
///
/// dataSource.bind(to: viewModel.items)
/// ```
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

    private weak var collectionView: UICollectionView?
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

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    // MARK: - Initialization

    /// Creates a new DeltaCollectionDataSource.
    /// - Parameters:
    ///   - collectionView: The collection view to manage.
    ///   - cellProvider: Closure that provides cells for loaded items.
    ///   - loadingCellProvider: Optional closure that provides cells for unloaded items (for soft lists).
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
                // Stream completed or was cancelled
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
                    } else if let nsValue = value as? NSObject {
                        // Cross-module: SKIE re-exports types with different names
                        self.applyDeltaViaKVC(nsValue)
                    }
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Stops collecting deltas.
    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application

    /// CRITICAL: Never access delta.items directly! It triggers NSArray bridging which
    /// iterates the ENTIRE list - catastrophic for soft lists with thousands of items.
    /// Always use delta.loadedItems() and delta.totalSize() instead.

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

    private func applyDeltaViaKVC(_ nsValue: NSObject) {
        currentDelta = nsValue

        // NEVER access "items" via KVC - it triggers the bridging catastrophe!
        // Use loadedItems() method instead
        var extractedItems: [T] = []

        // Try calling loadedItems() method
        if nsValue.responds(to: Selector(("loadedItems"))) {
            if let loadedArray = nsValue.perform(Selector(("loadedItems")))?.takeUnretainedValue() as? [AnyObject] {
                extractedItems = loadedArray.compactMap { $0 as? T }
            }
        }

        items = extractedItems

        // Get change object
        guard let changeObj = nsValue.value(forKey: "change") else {
            return
        }

        // Get totalSize for soft lists
        if nsValue.responds(to: Selector(("totalSize"))) {
            if let sizeResult = nsValue.perform(Selector(("totalSize")))?.takeUnretainedValue() as? NSNumber {
                totalSize = sizeResult.intValue
            } else {
                totalSize = items.count
            }
        } else {
            totalSize = items.count
        }

        onItemsChanged?(items)

        // On first data, always reload
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView?.reloadData()
            triggerLoadsForVisibleCells()
            return
        }

        // Determine change type and apply
        let typeName = String(describing: type(of: changeObj))

        if typeName.contains("Reload") {
            collectionView?.reloadData()
        } else if typeName.contains("Mutations"), let mutationsNS = changeObj as? NSObject {
            applyMutationsViaKVC(mutationsNS)
        } else {
            collectionView?.reloadData()
        }

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    private func applyChange(_ change: Change) {
        guard let collectionView = collectionView else { return }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView.reloadData()
            return
        }

        switch onEnum(of: change) {
        case .reload:
            collectionView.reloadData()

        case .mutations(let mutations):
            collectionView.performBatchUpdates {
                for operation in mutations.operations {
                    switch onEnum(of: operation) {
                    case .insert(let insert):
                        let indexPaths = (0..<Int(insert.count)).map {
                            IndexPath(item: Int(insert.index) + $0, section: 0)
                        }
                        collectionView.insertItems(at: indexPaths)

                    case .remove(let remove):
                        let indexPaths = (0..<Int(remove.count)).map {
                            IndexPath(item: Int(remove.index) + $0, section: 0)
                        }
                        collectionView.deleteItems(at: indexPaths)

                    case .update(let update):
                        let indexPaths = (0..<Int(update.count)).map {
                            IndexPath(item: Int(update.index) + $0, section: 0)
                        }
                        collectionView.reloadItems(at: indexPaths)

                    case .move(let move):
                        for i in 0..<Int(move.count) {
                            let from = IndexPath(item: Int(move.fromIndex) + i, section: 0)
                            let to = IndexPath(item: Int(move.toIndex) + i, section: 0)
                            collectionView.moveItem(at: from, to: to)
                        }
                    }
                }
            }
        }
    }

    private func applyMutationsViaKVC(_ mutationsNS: NSObject) {
        guard let operations = mutationsNS.value(forKey: "operations") as? [AnyObject] else {
            collectionView?.reloadData()
            return
        }

        collectionView?.performBatchUpdates {
            for operation in operations {
                guard let opNS = operation as? NSObject else { continue }
                let opType = String(describing: type(of: operation))

                if opType.contains("Insert") {
                    let index = (opNS.value(forKey: "index") as? Int) ?? 0
                    let count = (opNS.value(forKey: "count") as? Int) ?? 1
                    let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: 0) }
                    collectionView?.insertItems(at: indexPaths)
                } else if opType.contains("Remove") {
                    let index = (opNS.value(forKey: "index") as? Int) ?? 0
                    let count = (opNS.value(forKey: "count") as? Int) ?? 1
                    let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: 0) }
                    collectionView?.deleteItems(at: indexPaths)
                } else if opType.contains("Update") {
                    let index = (opNS.value(forKey: "index") as? Int) ?? 0
                    let count = (opNS.value(forKey: "count") as? Int) ?? 1
                    let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: 0) }
                    collectionView?.reloadItems(at: indexPaths)
                } else if opType.contains("Move") {
                    let fromIndex = (opNS.value(forKey: "fromIndex") as? Int) ?? 0
                    let toIndex = (opNS.value(forKey: "toIndex") as? Int) ?? 0
                    let count = (opNS.value(forKey: "count") as? Int) ?? 1
                    for i in 0..<count {
                        collectionView?.moveItem(
                            at: IndexPath(item: fromIndex + i, section: 0),
                            to: IndexPath(item: toIndex + i, section: 0)
                        )
                    }
                }
            }
        }
    }

    // MARK: - Soft List Support

    /// Returns true if the item at the given index is loaded (for soft lists).
    public func isLoadedAt(index: Int) -> Bool {
        // Try calling directly on Delta<T>
        if let delta = currentDelta as? Delta<T> {
            return delta.isLoadedAt(index: Int32(index))
        }
        // Try calling on Delta<AnyObject>
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.isLoadedAt(index: Int32(index))
        }
        // Fallback: item is loaded if it's within the items array
        return index < items.count
    }

    /// Returns the loaded item at the given index, or nil if not loaded (for soft lists).
    public func getLoadedItemAt(index: Int) -> T? {
        // Try calling directly on Delta<T>
        if let delta = currentDelta as? Delta<T> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        // Try calling on Delta<AnyObject>
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        // Fallback
        return index < items.count ? items[index] : nil
    }

    /// Triggers loading at the given index (for soft lists).
    public func triggerLoadAt(index: Int) {
        // Try calling directly on Delta<T>
        if let delta = currentDelta as? Delta<T> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
        // Try calling on Delta<AnyObject>
        if let delta = currentDelta as? Delta<AnyObject> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
    }

    /// Triggers loading for all visible cells that are not yet loaded.
    /// Call this after receiving new data to continue loading visible items.
    private func triggerLoadsForVisibleCells() {
        guard loadingCellProvider != nil else { return }  // Only for soft lists
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
        return totalSize
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let index = indexPath.item

        // Check if item is loaded
        if let item = getLoadedItemAt(index: index) {
            return cellProvider(collectionView, indexPath, item)
        } else if let loadingProvider = loadingCellProvider {
            // Show loading cell for unloaded items
            return loadingProvider(collectionView, indexPath)
        } else if index < items.count {
            // Fallback for regular lists
            return cellProvider(collectionView, indexPath, items[index])
        } else {
            // Should not happen, but return empty cell as safety
            fatalError("Index out of bounds: \(index) >= \(items.count), totalSize: \(totalSize)")
        }
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        return supplementaryViewProvider?(collectionView, kind, indexPath) ?? UICollectionReusableView()
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Trigger loading for unloaded items when they become visible
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

    /// Sets the supplementary view provider.
    public func setSupplementaryViewProvider(_ provider: SupplementaryViewProvider?) {
        self.supplementaryViewProvider = provider
    }

    // MARK: - Item Access

    /// Returns the item at the given index path (only loaded items).
    public func item(at indexPath: IndexPath) -> T? {
        return getLoadedItemAt(index: indexPath.item)
    }

    /// Returns the item at the given index (only loaded items).
    public func item(at index: Int) -> T? {
        return getLoadedItemAt(index: index)
    }

    deinit {
        task?.cancel()
    }
}

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

    /// Creates a data source for items with stable IDs.
    /// - Parameters:
    ///   - collectionView: The collection view to manage.
    ///   - stableIdExtractor: Closure that extracts the stable ID from an item.
    ///   - cellProvider: Closure that provides cells for items.
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
                // Stream completed
            }
        }
    }

    public func bind(erased stream: some AsyncSequence) {
        unbind()
        task = Task { @MainActor in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    // Try direct cast first, then try to extract items/change from Any
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        // The generic parameter might not match exactly due to module boundaries
                        // Create a wrapper that extracts the items as our expected type
                        let extractedItems = delta.items.compactMap { $0 as? T }
                        self.items = extractedItems
                        self.itemsByStableId = Dictionary(uniqueKeysWithValues: extractedItems.map { (self.stableIdExtractor($0), $0) })
                        self.onItemsChanged?(extractedItems)

                        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
                        snapshot.appendSections([0])
                        snapshot.appendItems(extractedItems.map { self.stableIdExtractor($0) }, toSection: 0)

                        let animating: Bool
                        switch onEnum(of: delta.change) {
                        case .reload:
                            animating = false
                        case .mutations:
                            animating = true
                        }

                        self.diffableDataSource.apply(snapshot, animatingDifferences: animating)
                    } else if let nsValue = value as? NSObject {
                        // Cross-module: SKIE re-exports types with different names (e.g., DemoCoreDelta)
                        // Use KVC to access properties
                        self.applyDeltaViaKVC(nsValue)
                    }
                }
            } catch {
                // Stream completed
            }
        }
    }

    /// Extracts Delta data using Key-Value Coding for cross-module compatibility.
    private func applyDeltaViaKVC(_ nsValue: NSObject) {
        // Get items array
        guard let itemsArray = nsValue.value(forKey: "items") as? [AnyObject] else {
            return
        }

        // Get change object
        guard let changeObj = nsValue.value(forKey: "change") else {
            return
        }

        // Extract items
        let extractedItems = itemsArray.compactMap { $0 as? T }
        self.items = extractedItems
        self.itemsByStableId = Dictionary(uniqueKeysWithValues: extractedItems.map { (self.stableIdExtractor($0), $0) })
        self.onItemsChanged?(extractedItems)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(extractedItems.map { self.stableIdExtractor($0) }, toSection: 0)

        // Determine if we should animate based on change type
        let typeName = String(describing: type(of: changeObj))
        let animating = !typeName.contains("Reload")

        self.diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items as! [T]
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating: Bool
        switch onEnum(of: delta.change) {
        case .reload:
            animating = false
        case .mutations:
            animating = true
        }

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
        // Subclasses can override to handle lazy item release
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Sectioned Delta Collection Data Source

/// Traditional UICollectionViewDataSource that observes a SectionedDeltaList.
/// NO DiffableDataSource - direct data source/delegate implementation with performBatchUpdates.
///
/// Usage:
/// ```swift
/// let dataSource = SectionedDeltaCollectionDataSource<SectionHeader, Item>(
///     collectionView: collectionView,
///     cellProvider: { collectionView, indexPath, item in
///         // return configured cell
///     },
///     headerProvider: { collectionView, indexPath, header in
///         // return configured header view
///     }
/// )
/// dataSource.bind(to: viewModel.sections)
/// ```
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

    private weak var collectionView: UICollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    private let cellProvider: CellProvider
    private let headerProvider: HeaderProvider?

    /// Callback when sections are updated.
    public var onSectionsChanged: (([SectionData]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback when a header is tapped (if headers are interactive).
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

    /// Binds to a SectionedDeltaList flow.
    public func bind(to flow: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    // Try direct cast first (same module)
                    if let sectionedDelta = value as? SectionedDelta<H, T> {
                        self.applySectionedDelta(sectionedDelta)
                    } else if let sectionedDelta = value as? SectionedDelta<AnyObject, AnyObject> {
                        self.applySectionedDeltaErased(sectionedDelta)
                    } else if let nsValue = value as? NSObject {
                        // Cross-module: SKIE re-exports types with different names (e.g., DemoCoreSectionedDelta)
                        // Use KVC to access properties
                        self.applySectionedDeltaViaKVC(nsValue)
                    }
                }
            } catch {}
        }
    }

    /// Extracts SectionedDelta data using Key-Value Coding for cross-module compatibility.
    private func applySectionedDeltaViaKVC(_ nsValue: NSObject) {
        // Get sections array
        guard let sectionsArray = nsValue.value(forKey: "sections") as? [AnyObject] else {
            print("[SectionedDataSource] Could not extract sections via KVC")
            return
        }

        // Get change object
        guard let changeObj = nsValue.value(forKey: "change") else {
            print("[SectionedDataSource] Could not extract change via KVC")
            return
        }

        // Convert sections
        let newSections = sectionsArray.compactMap { sectionObj -> SectionData? in
            guard let section = sectionObj as? NSObject,
                  let header = section.value(forKey: "header") as? H else {
                return nil
            }
            let items = (section.value(forKey: "items") as? [AnyObject])?.compactMap { $0 as? T } ?? []
            return SectionData(header: header, items: items)
        }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
            return
        }

        // Determine change type and apply
        let typeName = String(describing: type(of: changeObj))

        if typeName.contains("Reload") {
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
        } else if typeName.contains("Sections"), let changeNS = changeObj as? NSObject {
            // Section-level mutations
            sections = newSections
            onSectionsChanged?(newSections)

            if let mutations = changeNS.value(forKey: "mutations") as? [AnyObject] {
                collectionView?.performBatchUpdates {
                    for mutation in mutations {
                        guard let mutationNS = mutation as? NSObject else { continue }
                        let mutationType = String(describing: type(of: mutation))

                        if mutationType.contains("Insert") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            collectionView?.insertSections(IndexSet(index..<(index + count)))
                        } else if mutationType.contains("Remove") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            collectionView?.deleteSections(IndexSet(index..<(index + count)))
                        } else if mutationType.contains("Update") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            collectionView?.reloadSections(IndexSet(integer: index))
                        } else if mutationType.contains("Move") {
                            let fromIndex = (mutationNS.value(forKey: "fromIndex") as? Int) ?? 0
                            let toIndex = (mutationNS.value(forKey: "toIndex") as? Int) ?? 0
                            collectionView?.moveSection(fromIndex, toSection: toIndex)
                        }
                    }
                }
            } else {
                collectionView?.reloadData()
            }
        } else if typeName.contains("Items"), let changeNS = changeObj as? NSObject {
            // Item-level mutations
            sections = newSections
            onSectionsChanged?(newSections)

            let sectionIndex = (changeNS.value(forKey: "section") as? Int) ?? 0
            if let mutations = changeNS.value(forKey: "mutations") as? [AnyObject] {
                collectionView?.performBatchUpdates {
                    for mutation in mutations {
                        guard let mutationNS = mutation as? NSObject else { continue }
                        let mutationType = String(describing: type(of: mutation))

                        if mutationType.contains("Insert") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.insertItems(at: indexPaths)
                        } else if mutationType.contains("Remove") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.deleteItems(at: indexPaths)
                        } else if mutationType.contains("Update") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.reloadItems(at: indexPaths)
                        } else if mutationType.contains("Move") {
                            let fromIndex = (mutationNS.value(forKey: "fromIndex") as? Int) ?? 0
                            let toIndex = (mutationNS.value(forKey: "toIndex") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            for i in 0..<count {
                                collectionView?.moveItem(
                                    at: IndexPath(item: fromIndex + i, section: sectionIndex),
                                    to: IndexPath(item: toIndex + i, section: sectionIndex)
                                )
                            }
                        }
                    }
                }
            } else {
                collectionView?.reloadData()
            }
        } else {
            // Unknown change type, just reload
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application

    private func applySectionedDelta(_ delta: SectionedDelta<H, T>) {
        let newSections = delta.sections.compactMap { section -> SectionData? in
            guard let header = section.header else { return nil }
            let items = section.items.compactMap { $0 as? T }
            return SectionData(header: header, items: items)
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaErased(_ delta: SectionedDelta<AnyObject, AnyObject>) {
        let newSections = delta.sections.compactMap { section -> SectionData? in
            guard let header = section.header as? H else { return nil }
            let items = section.items.compactMap { $0 as? T }
            return SectionData(header: header, items: items)
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applyChanges(newSections: [SectionData], change: SectionedChange) {
        sections = newSections
        onSectionsChanged?(newSections)

        guard let collectionView = collectionView else { return }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView.reloadData()
            return
        }

        switch onEnum(of: change) {
        case .reload:
            collectionView.reloadData()

        case .sections(let sectionChanges):
            // Section-level mutations (add/remove/move/update sections)
            collectionView.performBatchUpdates {
                for mutation in sectionChanges.mutations {
                    switch onEnum(of: mutation) {
                    case .insert(let insert):
                        let sectionIndices = IndexSet(Int(insert.index)..<Int(insert.index + insert.count))
                        collectionView.insertSections(sectionIndices)

                    case .remove(let remove):
                        let sectionIndices = IndexSet(Int(remove.index)..<Int(remove.index + remove.count))
                        collectionView.deleteSections(sectionIndices)

                    case .update(let update):
                        collectionView.reloadSections(IndexSet(integer: Int(update.index)))

                    case .move(let move):
                        collectionView.moveSection(Int(move.fromIndex), toSection: Int(move.toIndex))
                    }
                }
            }

        case .items(let itemChanges):
            // Item-level mutations within a specific section
            let sectionIndex = Int(itemChanges.section)
            collectionView.performBatchUpdates {
                for mutation in itemChanges.mutations {
                    switch onEnum(of: mutation) {
                    case .insert(let insert):
                        let indexPaths = (0..<Int(insert.count)).map {
                            IndexPath(item: Int(insert.index) + $0, section: sectionIndex)
                        }
                        collectionView.insertItems(at: indexPaths)

                    case .remove(let remove):
                        let indexPaths = (0..<Int(remove.count)).map {
                            IndexPath(item: Int(remove.index) + $0, section: sectionIndex)
                        }
                        collectionView.deleteItems(at: indexPaths)

                    case .update(let update):
                        let indexPaths = (0..<Int(update.count)).map {
                            IndexPath(item: Int(update.index) + $0, section: sectionIndex)
                        }
                        collectionView.reloadItems(at: indexPaths)

                    case .move(let move):
                        for i in 0..<Int(move.count) {
                            let from = IndexPath(item: Int(move.fromIndex) + i, section: sectionIndex)
                            let to = IndexPath(item: Int(move.toIndex) + i, section: sectionIndex)
                            collectionView.moveItem(at: from, to: to)
                        }
                    }
                }
            }
        }
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        return sections.count
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            fatalError("Index out of bounds: section \(indexPath.section), item \(indexPath.item)")
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
#endif

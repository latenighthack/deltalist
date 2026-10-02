#if canImport(AppKit) && !canImport(UIKit)
import AppKit
import Foundation

/// Generic NSCollectionView data source that observes a DeltaList (Kotlin Flow<Delta<T>>).
/// The AppKit peer of `DeltaCollectionDataSource` in DeltaDataSource.swift, which is UIKit-only.
/// Mac Catalyst is excluded by the `!canImport(UIKit)` guard above: there the UIKit source applies.
///
/// Uses direct NSCollectionViewDataSource implementation with performBatchUpdates for efficient
/// updates. Supports both regular lists and soft/paginated lists.
///
/// CRITICAL: Never access delta.items directly! It triggers NSArray bridging which iterates the
/// ENTIRE list - catastrophic for soft lists. Always use delta.loadedItems() and delta.totalSize().
///
/// For soft/paginated lists, provide a `loadingItemProvider` to display loading placeholders.
/// The data source will automatically trigger loads when unloaded items become visible.
///
/// SCOPE: this is the flat (single-section) data source. The AppKit peers for
/// `SectionedDeltaCollectionDataSource` and `StableDeltaCollectionDataSource` now live alongside
/// it (`DeltaNSCollectionDataSource+Sectioned.swift` / `+Stable.swift`), and the `DeltaRows`
/// result-builder DSL has an AppKit peer in `DeltaNSRows.swift` - all added for the macOS demo
/// (`demo-ios/DemoAppMac`), the first consumer to need full AppKit parity with the UIKit bindings.
@available(macOS 11.0, *)
@MainActor
public class DeltaNSCollectionDataSource<T: AnyObject>: NSObject,
    NSCollectionViewDataSource,
    NSCollectionViewDelegate
{
    // MARK: - Types

    public typealias ItemProvider = (NSCollectionView, IndexPath, T) -> NSCollectionViewItem
    public typealias LoadingItemProvider = (NSCollectionView, IndexPath) -> NSCollectionViewItem
    public typealias SupplementaryViewProvider = (NSCollectionView, NSCollectionView.SupplementaryElementKind, IndexPath) -> NSView?

    // MARK: - Properties

    weak var collectionView: NSCollectionView?
    private(set) public var items: [T] = []
    private var task: Task<Void, Never>?
    private var rowLeases: [ObjectIdentifier: DeltaItemLease<T>] = [:]
    private var hasReceivedInitialData = false

    // Soft list support - store the current delta for pagination methods
    private var currentDelta: AnyObject?

    private let itemProvider: ItemProvider
    private let loadingItemProvider: LoadingItemProvider?
    private var supplementaryViewProvider: SupplementaryViewProvider?

    /// The total size of the list (including unloaded items for soft lists).
    private(set) public var totalSize: Int = 0

    /// The item count the collection view currently reflects. Stepped one mutation at a
    /// time while a Change.Mutations is applied so that each per-operation batch update
    /// stays internally consistent (running-index semantics). Equals totalSize at rest.
    private var appliedCount: Int = 0

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Callback when an item is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback invoked when the bound delta stream terminates with an error. Defaults to
    /// nil (silent). Wire this to your telemetry/logging so upstream failures are visible
    /// instead of looking identical to normal completion.
    public var onError: ((Error) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: NSCollectionView,
        itemProvider: @escaping ItemProvider,
        loadingItemProvider: LoadingItemProvider? = nil
    ) {
        self.collectionView = collectionView
        self.itemProvider = itemProvider
        self.loadingItemProvider = loadingItemProvider
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
                if !Task.isCancelled && !(error is CancellationError) { self?.onError?(error) }
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
                if !Task.isCancelled && !(error is CancellationError) { self?.onError?(error) }
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
        rowLeases.values.forEach { $0.release() }
        rowLeases.removeAll()
    }

    /// Sets the binding task for external collectors (e.g., MoveableDeltaList extensions).
    public func setBindingTask(_ newTask: Task<Void, Never>) {
        unbind()
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

        // Continue loading any visible items that are still unloaded
        triggerLoadsForVisibleItems()
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        currentDelta = delta

        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }

        totalSize = Int(delta.totalSize())

        onItemsChanged?(items)
        applyChange(delta.change)

        triggerLoadsForVisibleItems()
    }

    /// Apply delta from any type (cross-module compatibility). Reads structure through
    /// `DeltaProtocol`, declared outside DeltaDataSource.swift's UIKit guard in DeltaList.swift
    /// precisely so every Apple platform can share it.
    private func applyDeltaAny(_ delta: AnyObject) {
        currentDelta = delta

        // NEVER access "items" property - it triggers the bridging catastrophe!
        // Use loadedItems() method instead
        var extractedItems: [T] = []

        extractedItems = loadedItemsViaRuntime(delta).compactMap { $0 as? T }

        items = extractedItems

        if let deltaProto = delta as? DeltaProtocol {
            totalSize = Int(deltaProto.totalSize())
        } else {
            totalSize = totalSizeViaRuntime(delta)
        }

        onItemsChanged?(items)

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

        triggerLoadsForVisibleItems()
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
        // coordinates and throw whenever a Move is mixed with structural ops. Instead apply
        // each operation in its own batch, stepping appliedCount so numberOfItemsInSection
        // stays consistent at every step.
        //
        // If the stream is inconsistent with the snapshot's size change (e.g. an upstream
        // desync), fall back to a full reload rather than crash.
        guard mutationsAreConsistent(mutations.operations, startCount: appliedCount, endCount: totalSize) else {
            appliedCount = totalSize
            collectionView.reloadData()
            return
        }

        for operation in mutations.operations {
            // AppKit takes Set<IndexPath> (UIKit takes [IndexPath]) and requires the
            // completionHandler argument explicitly.
            collectionView.performBatchUpdates({
                if let insert = operation as? Mutation.Insert {
                    let indexPaths = Set((0..<Int(insert.count)).map {
                        IndexPath(item: Int(insert.index) + $0, section: 0)
                    })
                    self.appliedCount += Int(insert.count)
                    collectionView.insertItems(at: indexPaths)
                } else if let remove = operation as? Mutation.Remove {
                    let indexPaths = Set((0..<Int(remove.count)).map {
                        IndexPath(item: Int(remove.index) + $0, section: 0)
                    })
                    self.appliedCount -= Int(remove.count)
                    collectionView.deleteItems(at: indexPaths)
                } else if let update = operation as? Mutation.Update {
                    let indexPaths = Set((0..<Int(update.count)).map {
                        IndexPath(item: Int(update.index) + $0, section: 0)
                    })
                    collectionView.reloadItems(at: indexPaths)
                } else if let move = operation as? Mutation.Move {
                    // Single-item move per the Change contract; count is unchanged.
                    let from = IndexPath(item: Int(move.fromIndex), section: 0)
                    let to = IndexPath(item: Int(move.toIndex), section: 0)
                    collectionView.moveItem(at: from, to: to)
                }
            }, completionHandler: nil)
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
        if let delta = currentDelta { return isLoadedAtViaRuntime(delta, index: Int32(index)) }
        return index >= 0 && index < items.count
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
        if let delta = currentDelta { return loadedItemAtViaRuntime(delta, index: Int32(index)) as? T }
        return index >= 0 && index < items.count ? items[index] : nil
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
        if let delta = currentDelta { triggerLoadAtViaRuntime(delta, index: Int32(index)) }
    }

    /// Triggers loading for all visible items that are not yet loaded.
    private func triggerLoadsForVisibleItems() {
        guard loadingItemProvider != nil else { return }
        guard let collectionView = collectionView else { return }

        // AppKit spells this as a method returning Set<IndexPath>; UIKit uses a property.
        for indexPath in collectionView.indexPathsForVisibleItems() {
            let index = indexPath.item
            if !isLoadedAt(index: index) {
                triggerLoadAt(index: index)
            }
        }
    }

    /// A blank item used when no provider can supply one. `NSCollectionViewItem` is an
    /// `NSViewController`, so its `view` must be assigned explicitly - the default `loadView()`
    /// raises without a nib.
    private func placeholderItem() -> NSCollectionViewItem {
        let item = NSCollectionViewItem()
        item.view = NSView()
        return item
    }

    // MARK: - NSCollectionViewDataSource

    public func numberOfSections(in collectionView: NSCollectionView) -> Int {
        return 1
    }

    public func collectionView(_ collectionView: NSCollectionView, numberOfItemsInSection section: Int) -> Int {
        // Reflects intermediate state while a Change.Mutations is being applied; equals
        // totalSize at rest.
        return appliedCount
    }

    public func collectionView(_ collectionView: NSCollectionView, itemForRepresentedObjectAt indexPath: IndexPath) -> NSCollectionViewItem {
        let index = indexPath.item

        if let delta = currentDelta, let lease: DeltaItemLease<T> = acquireDeltaItem(delta, index: index) {
            let rendered = itemProvider(collectionView, indexPath, lease.item)
            rowLeases.updateValue(lease, forKey: ObjectIdentifier(rendered))?.release()
            return rendered
        } else if let item = getLoadedItemAt(index: index) {
            return itemProvider(collectionView, indexPath, item)
        } else if let loadingProvider = loadingItemProvider {
            return loadingProvider(collectionView, indexPath)
        } else if index < items.count {
            return itemProvider(collectionView, indexPath, items[index])
        } else {
            // Never fatalError in an item provider: a transient inconsistency during an
            // animated update must be recoverable, not a hard crash. Return an empty
            // placeholder; the next consistent snapshot will reconcile.
            return loadingItemProvider?(collectionView, indexPath) ?? placeholderItem()
        }
    }

    public func collectionView(_ collectionView: NSCollectionView, viewForSupplementaryElementOfKind kind: NSCollectionView.SupplementaryElementKind, at indexPath: IndexPath) -> NSView {
        return supplementaryViewProvider?(collectionView, kind, indexPath) ?? NSView()
    }

    // MARK: - NSCollectionViewDelegate

    public func collectionView(_ collectionView: NSCollectionView, willDisplay item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        let index = indexPath.item
        if !isLoadedAt(index: index) {
            triggerLoadAt(index: index)
        }
    }

    public func collectionView(_ collectionView: NSCollectionView, didEndDisplaying item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        rowLeases.removeValue(forKey: ObjectIdentifier(item))?.release()
    }

    /// AppKit reports selection as a set (UIKit reports a single index path), so each selected
    /// index path is surfaced individually through `onItemSelected`.
    public func collectionView(_ collectionView: NSCollectionView, didSelectItemsAt indexPaths: Set<IndexPath>) {
        for indexPath in indexPaths.sorted() {
            if let item = getLoadedItemAt(index: indexPath.item) {
                onItemSelected?(indexPath, item)
            }
        }
        collectionView.deselectItems(at: indexPaths)
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

#endif

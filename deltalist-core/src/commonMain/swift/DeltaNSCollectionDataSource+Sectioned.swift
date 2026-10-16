#if canImport(AppKit) && !canImport(UIKit)
import AppKit
import Foundation

/// AppKit peer of `SectionedDeltaCollectionDataSource` (UIKit, DeltaDataSource.swift). Observes a
/// `SectionedDeltaList` (Kotlin `Flow<SectionedDelta<H, T>>`) and drives an `NSCollectionView` with
/// direct data source/delegate + `performBatchUpdates` (no diffable data source).
///
/// Mac Catalyst is excluded by `!canImport(UIKit)`: there the UIKit source applies.
///
/// Rows are read through the snapshot as the UIKit peer reads them: each visible item leases its
/// row until it ends displaying or shows another row, so a lazily mapped section (`lazyMapItems`)
/// hands every item its cached row. Rows are never peeked on a delta, and `delta.sections` is
/// never read (bridging it force-loads the entire list). Sections and rows are keyed by Kotlin's
/// `SectionedStableIds` (`sectionKey(at:)`, `itemKey(at:)`).
@available(macOS 11.0, *)
@MainActor
public class SectionedDeltaNSCollectionDataSource<H: AnyObject, T: AnyObject>: NSObject,
    NSCollectionViewDataSource,
    NSCollectionViewDelegate
{
    // MARK: - Types

    public typealias ItemProvider = (NSCollectionView, IndexPath, T) -> NSCollectionViewItem
    public typealias HeaderProvider = (NSCollectionView, IndexPath, H) -> NSView

    // MARK: - Section Data

    /// One section of the current emission.
    public struct SectionData {
        public let header: H
        /// The section's loaded rows, read through the current snapshot (see `DeltaSectionItems`).
        public let items: DeltaSectionItems<T>
        /// Session-stable key of the section, following it through section changes.
        public let key: Int
        /// Session-stable key of each row, following it through changes.
        public let itemKeys: [Int]

        /// A hand-built section; its keys are positional.
        public init(header: H, items: [T]) {
            self.header = header
            self.items = DeltaSectionItems(items)
            self.key = 0
            self.itemKeys = Array(items.indices)
        }

        init(header: H, snapshot: SectionedDeltaSnapshot, section: Int) {
            self.header = header
            self.items = DeltaSectionItems(snapshot: snapshot, section: section)
            self.key = snapshot.sectionKeys[section]
            self.itemKeys = snapshot.itemKeys[section]
        }
    }

    // MARK: - Properties

    weak var collectionView: NSCollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false
    private var snapshot = SectionedDeltaSnapshot.empty
    private let leases = DeltaCellLeases<T>()

    // Stepped counts so per-operation batch updates stay consistent during application
    // (mirrors the flat AppKit data source). At rest, appliedSectionCount == sections.count
    // and applyingSection == -1.
    private var appliedSectionCount: Int = 0
    private var applyingSection: Int = -1
    private var applyingItemCount: Int = 0
    private var applyingSections: [SectionData]?

    private let itemProvider: ItemProvider
    private let headerProvider: HeaderProvider?

    /// Callback when sections are updated.
    public var onSectionsChanged: (([SectionData]) -> Void)?

    /// Callback invoked when the bound delta stream terminates with an error. Defaults to
    /// nil (silent). Wire this to telemetry to make upstream failures visible.
    public var onError: ((Error) -> Void)?

    /// Callback when an item is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback when a header is tapped.
    public var onHeaderSelected: ((Int, H) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: NSCollectionView,
        itemProvider: @escaping ItemProvider,
        headerProvider: HeaderProvider? = nil
    ) {
        self.collectionView = collectionView
        self.itemProvider = itemProvider
        self.headerProvider = headerProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    public func bind(to flow: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        snapshot = .empty
        task = Task { @MainActor [weak self] in
            do {
                for try await value in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }
                    self.applySectionedDelta(value as AnyObject)
                }
            } catch {
                self?.onError?(error)
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
        leases.releaseAll()
    }

    /// Sets the binding task for external collectors.
    public func setBindingTask(_ newTask: Task<Void, Never>) {
        task = newTask
    }

    // MARK: - Delta Application
    // CRITICAL: Never access delta.sections directly, and never peek rows: read through the
    // snapshot so the Kotlin backing list is not bridged and cached rows are reused.

    private func applySectionedDelta(_ delta: AnyObject) {
        let next = SectionedDeltaSnapshot(delta, previousIds: snapshot.ids)
        let newSections = next.headers.indices.compactMap { index in
            (next.headers[index] as? H).map { SectionData(header: $0, snapshot: next, section: index) }
        }
        // A distinct framework's change can't be cast; its keys still follow it in Kotlin.
        let change = (delta as? SectionedDelta<AnyObject, AnyObject>)?.change
            ?? (changeViaRuntime(delta) as? SectionedChange)
        snapshot = next

        let oldSections = sections
        let oldItemCounts = sections.map { $0.items.count }
        sections = newSections
        onSectionsChanged?(newSections)

        guard collectionView != nil else { return }
        guard hasReceivedInitialData, let change, newSections.count == next.headers.count else {
            hasReceivedInitialData = true
            reloadAll()
            return
        }
        applySectionedChange(change, oldSections: oldSections, oldItemCounts: oldItemCounts)
    }

    /// Reloads every section. Rows that stay visible keep their cached objects.
    private func reloadAll() {
        guard let collectionView = collectionView else { return }
        leases.deferringReleases {
            appliedSectionCount = sections.count
            collectionView.reloadData()
            collectionView.layoutSubtreeIfNeeded()
        }
    }

    private func applySectionedChange(_ change: SectionedChange, oldSections: [SectionData], oldItemCounts: [Int]) {
        guard let collectionView = collectionView else { return }

        if change is SectionedChange.Reload {
            reloadAll()
            return
        }

        if let sectionChanges = change as? SectionedChange.Sections {
            // Section-level mutations in running coordinates: apply one per batch, stepping
            // appliedSectionCount (which numberOfSections returns during application).
            guard let states = deltaSectionStates(sectionChanges.mutations, old: oldSections,
                                                  new: sections, itemCount: { $0.items.count }) else {
                reloadAll()
                return
            }
            appliedSectionCount = oldSections.count
            applyingSections = oldSections
            defer { applyingSections = nil }
            for (index, mutation) in sectionChanges.mutations.enumerated() {
                collectionView.performBatchUpdates({
                    self.applyingSections = states[index]
                    if let insert = mutation as? SectionMutation.Insert {
                        let c = Int(insert.count)
                        self.appliedSectionCount += c
                        collectionView.insertSections(IndexSet(Int(insert.index)..<Int(insert.index) + c))
                    } else if let remove = mutation as? SectionMutation.Remove {
                        let c = Int(remove.count)
                        self.appliedSectionCount -= c
                        collectionView.deleteSections(IndexSet(Int(remove.index)..<Int(remove.index) + c))
                    } else if let update = mutation as? SectionMutation.Update {
                        collectionView.reloadSections(IndexSet(integer: Int(update.index)))
                    } else if let move = mutation as? SectionMutation.Move {
                        collectionView.moveSection(Int(move.fromIndex), toSection: Int(move.toIndex))
                    }
                }, completionHandler: nil)
            }
            appliedSectionCount = sections.count
            return
        }

        if let itemChanges = change as? SectionedChange.Items {
            let sectionIndex = Int(itemChanges.section)
            guard sectionIndex >= 0, sectionIndex < sections.count, sectionIndex < oldItemCounts.count,
                  sections.count == oldSections.count else {
                reloadAll()
                return
            }
            let startCount = oldItemCounts[sectionIndex]
            let endCount = sections[sectionIndex].items.count
            guard mutationsAreConsistent(itemChanges.mutations, startCount: startCount, endCount: endCount) else {
                leases.deferringReleases {
                    collectionView.reloadSections(IndexSet(integer: sectionIndex))
                    collectionView.layoutSubtreeIfNeeded()
                }
                return
            }

            applyingSection = sectionIndex
            applyingItemCount = startCount
            for mutation in itemChanges.mutations {
                collectionView.performBatchUpdates({
                    if let insert = mutation as? Mutation.Insert {
                        let c = Int(insert.count)
                        let indexPaths = Set((0..<c).map { IndexPath(item: Int(insert.index) + $0, section: sectionIndex) })
                        self.applyingItemCount += c
                        collectionView.insertItems(at: indexPaths)
                    } else if let remove = mutation as? Mutation.Remove {
                        let c = Int(remove.count)
                        let indexPaths = Set((0..<c).map { IndexPath(item: Int(remove.index) + $0, section: sectionIndex) })
                        self.applyingItemCount -= c
                        collectionView.deleteItems(at: indexPaths)
                    } else if let update = mutation as? Mutation.Update {
                        let indexPaths = Set((0..<Int(update.count)).map { IndexPath(item: Int(update.index) + $0, section: sectionIndex) })
                        collectionView.reloadItems(at: indexPaths)
                    } else if let move = mutation as? Mutation.Move {
                        let from = IndexPath(item: Int(move.fromIndex), section: sectionIndex)
                        let to = IndexPath(item: Int(move.toIndex), section: sectionIndex)
                        collectionView.moveItem(at: from, to: to)
                    }
                }, completionHandler: nil)
            }
            applyingSection = -1
            return
        }

        reloadAll()
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

    // MARK: - NSCollectionViewDataSource

    public func numberOfSections(in collectionView: NSCollectionView) -> Int {
        // Reflects intermediate state while section-level mutations are applied.
        return appliedSectionCount
    }

    public func collectionView(_ collectionView: NSCollectionView, numberOfItemsInSection section: Int) -> Int {
        let sections = applyingSections ?? self.sections
        // Reflects intermediate state for the section currently being mutated.
        if section == applyingSection { return applyingItemCount }
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: NSCollectionView, itemForRepresentedObjectAt indexPath: IndexPath) -> NSCollectionViewItem {
        let sections = applyingSections ?? self.sections
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            // Never fatalError in an item provider: a transient inconsistency during an
            // animated update must be recoverable. Return an empty placeholder.
            return placeholderItem()
        }
        let items = sections[indexPath.section].items
        guard let lease = items.acquire(indexPath.item) else {
            // A hand-built or superseded section: show its row without pinning it.
            guard let row = items.element(indexPath.item) else { return placeholderItem() }
            return itemProvider(collectionView, indexPath, row)
        }
        let item = itemProvider(collectionView, indexPath, lease.item)
        leases.hold(lease, for: item)
        return item
    }

    public func collectionView(_ collectionView: NSCollectionView, viewForSupplementaryElementOfKind kind: NSCollectionView.SupplementaryElementKind, at indexPath: IndexPath) -> NSView {
        let sections = applyingSections ?? self.sections
        guard kind == NSCollectionView.elementKindSectionHeader,
              let headerProvider = headerProvider,
              indexPath.section < sections.count else {
            return NSView()
        }
        let header = sections[indexPath.section].header
        return headerProvider(collectionView, indexPath, header)
    }

    // MARK: - NSCollectionViewDelegate

    /// AppKit reports selection as a set (UIKit reports a single index path), so each selected
    /// index path is surfaced individually through `onItemSelected`.
    public func collectionView(_ collectionView: NSCollectionView, didSelectItemsAt indexPaths: Set<IndexPath>) {
        for indexPath in indexPaths.sorted() {
            guard let item = item(at: indexPath) else { continue }
            onItemSelected?(indexPath, item)
        }
        collectionView.deselectItems(at: indexPaths)
    }

    /// Releases the item's row lease. Subclasses that override this must call super.
    public func collectionView(_ collectionView: NSCollectionView, didEndDisplaying item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        leases.release(for: item)
    }

    // MARK: - Item Access

    /// The row at [indexPath], read through the snapshot: a visible row is its item's object.
    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.section < sections.count else { return nil }
        return sections[indexPath.section].items.element(indexPath.item)
    }

    public func header(at section: Int) -> H? {
        guard section < sections.count else { return nil }
        return sections[section].header
    }

    /// Session-stable key of the section at [section] (`SectionedStableIds`).
    public func sectionKey(at section: Int) -> Int? {
        guard section < sections.count else { return nil }
        return sections[section].key
    }

    /// Session-stable key of the row at [indexPath] (`SectionedStableIds`).
    public func itemKey(at indexPath: IndexPath) -> Int? {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].itemKeys.count else { return nil }
        return sections[indexPath.section].itemKeys[indexPath.item]
    }

    /// A blank item used when no provider can supply one. `NSCollectionViewItem` is an
    /// `NSViewController`, so its `view` must be assigned explicitly - the default `loadView()`
    /// raises without a nib.
    private func placeholderItem() -> NSCollectionViewItem {
        let item = NSCollectionViewItem()
        item.view = NSView()
        return item
    }

    deinit {
        task?.cancel()
    }
}

#endif

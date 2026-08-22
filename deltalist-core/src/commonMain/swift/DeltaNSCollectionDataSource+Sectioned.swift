#if canImport(AppKit) && !canImport(UIKit)
import AppKit
import Foundation

/// AppKit peer of `SectionedDeltaCollectionDataSource` (UIKit, DeltaDataSource.swift). Observes a
/// `SectionedDeltaList` (Kotlin `Flow<SectionedDelta<H, T>>`) and drives an `NSCollectionView` with
/// direct data source/delegate + `performBatchUpdates` (no diffable data source).
///
/// Mac Catalyst is excluded by `!canImport(UIKit)`: there the UIKit source applies.
///
/// CRITICAL: Never access `delta.sections` directly! It triggers NSArray bridging which force-loads
/// the ENTIRE list. Always use the Kotlin accessor methods (`sectionCount()`, `getHeaderAt`,
/// `getItemCountAt`, `getItemAt`) as the UIKit peer does.
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

    public struct SectionData {
        public let header: H
        public var items: [T]

        public init(header: H, items: [T]) {
            self.header = header
            self.items = items
        }
    }

    // MARK: - Properties

    weak var collectionView: NSCollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    // Stepped counts so per-operation batch updates stay consistent during application
    // (mirrors the flat AppKit data source). At rest, appliedSectionCount == sections.count
    // and applyingSection == -1.
    private var appliedSectionCount: Int = 0
    private var applyingSection: Int = -1
    private var applyingItemCount: Int = 0

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

    /// Sets the binding task for external collectors.
    public func setBindingTask(_ newTask: Task<Void, Never>) {
        task = newTask
    }

    // MARK: - Delta Application
    // CRITICAL: Never access delta.sections directly! Use Kotlin helper methods to avoid bridging.

    private func applySectionedDelta(_ delta: SectionedDelta<H, T>) {
        let sectionCount = Int(delta.sectionCount())
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

    private func applySectionedDeltaErased(_ delta: SectionedDelta<AnyObject, AnyObject>) {
        let sectionCount = Int(delta.sectionCount())
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

    private func applySectionedDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: read structure through the shared runtime-selector helpers
        // (DeltaSelector / DeltaIMPCache live in DeltaList.swift, outside any UIKit guard).
        let sectionCount = callSectionCountViaRuntime(delta)

        if sectionCount == 0 {
            // Distinguish "no sections" from "call failed" with one more typed cast.
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

        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = callGetHeaderAt(delta, sectionIndex: Int32(sectionIdx)) as? H else { continue }
            let itemCount = callGetItemCountAt(delta, sectionIndex: Int32(sectionIdx))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = callGetItemAt(delta, sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        let change = callGetChange(delta)

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

    // MARK: - Cross-module runtime helpers

    private func callSectionCountViaRuntime(_ obj: AnyObject) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> Int32
        let sel = DeltaSelector.sectionCount
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
        return Int(unsafeBitCast(imp, to: MethodType.self)(obj, sel))
    }

    private func callGetChange(_ obj: AnyObject) -> SectionedChange? {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> AnyObject?
        let sel = DeltaSelector.change
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel) as? SectionedChange
    }

    private func callGetHeaderAt(_ obj: AnyObject, sectionIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
        let sel = DeltaSelector.getHeaderAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex)
    }

    private func callGetItemCountAt(_ obj: AnyObject, sectionIndex: Int32) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> Int32
        let sel = DeltaSelector.getItemCountAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
        return Int(unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex))
    }

    private func callGetItemAt(_ obj: AnyObject, sectionIndex: Int32, itemIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32, Int32) -> AnyObject?
        let sel = DeltaSelector.getItemAt
        guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
        return unsafeBitCast(imp, to: MethodType.self)(obj, sel, sectionIndex, itemIndex)
    }

    // MARK: - Change Application

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
                collectionView.performBatchUpdates({
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

    // MARK: - NSCollectionViewDataSource

    public func numberOfSections(in collectionView: NSCollectionView) -> Int {
        // Reflects intermediate state while section-level mutations are applied.
        return appliedSectionCount
    }

    public func collectionView(_ collectionView: NSCollectionView, numberOfItemsInSection section: Int) -> Int {
        // Reflects intermediate state for the section currently being mutated.
        if section == applyingSection { return applyingItemCount }
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: NSCollectionView, itemForRepresentedObjectAt indexPath: IndexPath) -> NSCollectionViewItem {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            // Never fatalError in an item provider: a transient inconsistency during an
            // animated update must be recoverable. Return an empty placeholder.
            return placeholderItem()
        }
        let item = sections[indexPath.section].items[indexPath.item]
        return itemProvider(collectionView, indexPath, item)
    }

    public func collectionView(_ collectionView: NSCollectionView, viewForSupplementaryElementOfKind kind: NSCollectionView.SupplementaryElementKind, at indexPath: IndexPath) -> NSView {
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
            guard indexPath.section < sections.count,
                  indexPath.item < sections[indexPath.section].items.count else { continue }
            let item = sections[indexPath.section].items[indexPath.item]
            onItemSelected?(indexPath, item)
        }
        collectionView.deselectItems(at: indexPaths)
    }

    public func collectionView(_ collectionView: NSCollectionView, didEndDisplaying item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        // Subclasses can override to handle lazy item release / per-row state teardown.
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

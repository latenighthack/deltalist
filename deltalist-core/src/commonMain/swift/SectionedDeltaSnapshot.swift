import Foundation

/// Selector-only mirror of the Kotlin accessors that sectioned native bindings read rows and keys
/// through. Nothing conforms to it; it exists solely so `#selector` can derive checked selectors.
/// The names are explicit: Swift would otherwise insert "With" (`trackStableIdsWithPrevious:`)
/// where Kotlin does not.
@objc private protocol SectionedRowsRuntimeABI {
    @objc(getLoadedItemCountAtSectionIndex:) func getLoadedItemCountAt(sectionIndex: Int32) -> Int32
    @objc(acquireItemAtSectionIndex:itemIndex:) func acquireItemAt(sectionIndex: Int32, itemIndex: Int32) -> AnyObject?
    @objc(trackStableIdsPrevious:) func trackStableIds(previous: AnyObject?) -> AnyObject
    @objc(sectionIdSectionIndex:) func sectionId(sectionIndex: Int32) -> Int32
    @objc(itemIdSectionIndex:itemIndex:) func itemId(sectionIndex: Int32, itemIndex: Int32) -> Int32
    @objc(triggerLoadAtSectionIndex:itemIndex:) func triggerLoadAt(sectionIndex: Int32, itemIndex: Int32)
}

private enum SectionedRowsSelector {
    static let loadedItemCount = #selector(SectionedRowsRuntimeABI.getLoadedItemCountAt(sectionIndex:))
    static let acquireItem = #selector(SectionedRowsRuntimeABI.acquireItemAt(sectionIndex:itemIndex:))
    static let trackStableIds = #selector(SectionedRowsRuntimeABI.trackStableIds(previous:))
    static let sectionId = #selector(SectionedRowsRuntimeABI.sectionId(sectionIndex:))
    static let itemId = #selector(SectionedRowsRuntimeABI.itemId(sectionIndex:itemIndex:))
    static let triggerLoad = #selector(SectionedRowsRuntimeABI.triggerLoadAt(sectionIndex:itemIndex:))
}

/// One `SectionedDelta` as a native sectioned binding reads it: headers, row counts and stable
/// keys, with rows leased through the snapshot.
///
/// Every read goes through the exported Kotlin accessors by selector, so a delta from a consumer
/// framework (a distinct Obj-C class) reads the same way. Rows are never peeked: a lazily mapped
/// section returns its cached row to `acquire`, while a peek would build a new row each time.
/// Counts are each section's contiguous loaded prefix, read without building rows.
///
/// Keys come from Kotlin's `SectionedStableIds` (`trackStableIds`), which follows sections and
/// rows through `Sections` and `Items` changes even when Swift cannot read the change itself.
final class SectionedDeltaSnapshot {
    let delta: AnyObject?
    /// The emission's `SectionedIds`, owned by the delta's framework. Pass it to the next read.
    let ids: AnyObject?
    let headers: [AnyObject?]
    let counts: [Int]
    let sectionKeys: [Int]
    let itemKeys: [[Int]]

    static let empty = SectionedDeltaSnapshot(delta: nil, ids: nil, headers: [], counts: [], sectionKeys: [], itemKeys: [])

    private init(delta: AnyObject?, ids: AnyObject?, headers: [AnyObject?], counts: [Int],
                 sectionKeys: [Int], itemKeys: [[Int]]) {
        self.delta = delta
        self.ids = ids
        self.headers = headers
        self.counts = counts
        self.sectionKeys = sectionKeys
        self.itemKeys = itemKeys
    }

    /// Reads [delta], following the keys of the previous emission's [previousIds].
    convenience init(_ delta: AnyObject, previousIds: AnyObject?) {
        let count = sectionCountViaRuntime(delta)
        let ids = trackStableIdsViaRuntime(delta, previous: previousIds)
        var headers: [AnyObject?] = []
        var counts: [Int] = []
        var sectionKeys: [Int] = []
        var itemKeys: [[Int]] = []
        for section in 0..<count {
            let index = Int32(section)
            let rows = loadedItemCountViaRuntime(delta, sectionIndex: index)
            headers.append(headerViaRuntime(delta, sectionIndex: index) as AnyObject?)
            counts.append(rows)
            sectionKeys.append(ids.map { sectionIdViaRuntime($0, sectionIndex: index) } ?? section)
            itemKeys.append((0..<rows).map { item in
                ids.map { itemIdViaRuntime($0, sectionIndex: index, itemIndex: Int32(item)) } ?? item
            })
        }
        self.init(delta: delta, ids: ids, headers: headers, counts: counts, sectionKeys: sectionKeys, itemKeys: itemKeys)
    }

    /// Leases the row at [item] of [section], or nil when it is out of range, unloaded, or this
    /// snapshot was superseded. A lazily mapped row stays cached until the lease is released.
    func acquire<T: AnyObject>(section: Int, item: Int) -> DeltaItemLease<T>? {
        guard let delta, counts.indices.contains(section), item >= 0, item < counts[section] else { return nil }
        return acquireSectionedDeltaItem(delta, section: section, item: item)
    }

    /// Asks [section] for its slot at [item] when it isn't loaded, which fetches the next page of a
    /// paged section. Does nothing for a loaded or absent slot, or a superseded snapshot.
    func requestLoad(section: Int, item: Int) {
        guard let delta, counts.indices.contains(section), item >= 0 else { return }
        triggerLoadViaRuntime(delta, sectionIndex: Int32(section), itemIndex: Int32(item))
    }

    /// The row at [item] of [section] through the cache: the cached row while anything leases
    /// it, else a row built for this read. Falls back to a peek for a superseded snapshot.
    func read<T: AnyObject>(section: Int, item: Int) -> T? {
        if let lease: DeltaItemLease<T> = acquire(section: section, item: item) {
            defer { lease.release() }
            return lease.item
        }
        guard let delta, counts.indices.contains(section), item >= 0, item < counts[section] else { return nil }
        return itemViaRuntime(delta, sectionIndex: Int32(section), itemIndex: Int32(item)) as? T
    }
}

/// The loaded rows of one section of a sectioned data source, read through the snapshot that
/// produced them. Reading a row a cell shows returns that cell's object; reading any other row
/// builds it for that read when the section is lazily mapped. Counting never builds rows.
public struct DeltaSectionItems<T: AnyObject>: RandomAccessCollection {
    private let snapshot: SectionedDeltaSnapshot?
    private let section: Int
    private let rows: [T]?

    public let startIndex = 0
    public let endIndex: Int

    init(snapshot: SectionedDeltaSnapshot, section: Int) {
        self.snapshot = snapshot
        self.section = section
        self.rows = nil
        self.endIndex = snapshot.counts[section]
    }

    /// Rows held in memory, for sections built by hand.
    public init(_ rows: [T]) {
        self.snapshot = nil
        self.section = 0
        self.rows = rows
        self.endIndex = rows.count
    }

    public subscript(position: Int) -> T {
        guard let row = element(position) else {
            preconditionFailure("Row \(position) of section \(section) is not readable as \(T.self)")
        }
        return row
    }

    /// The row at [position], or nil when it is out of range or not a [T].
    func element(_ position: Int) -> T? {
        if let rows { return rows.indices.contains(position) ? rows[position] : nil }
        return snapshot?.read(section: section, item: position)
    }

    /// Leases the row at [position]; nil for hand-built sections or a superseded snapshot.
    func acquire(_ position: Int) -> DeltaItemLease<T>? {
        snapshot?.acquire(section: section, item: position)
    }
}

/// Row leases owned by the cells of one sectioned data source, keyed by cell.
///
/// While the collection view reloads, a lease displaced from a cell (because the cell now shows
/// another row, or ended displaying) is released only after every visible cell has acquired its
/// new row. A row that moves to another cell during the reload therefore keeps its cached object.
@MainActor
final class DeltaCellLeases<T: AnyObject> {
    private var leases: [ObjectIdentifier: DeltaItemLease<T>] = [:]
    private var deferred: [DeltaItemLease<T>]?

    func hold(_ lease: DeltaItemLease<T>, for cell: AnyObject) {
        if let previous = leases.updateValue(lease, forKey: ObjectIdentifier(cell)) { retire(previous) }
    }

    func release(for cell: AnyObject) {
        if let lease = leases.removeValue(forKey: ObjectIdentifier(cell)) { retire(lease) }
    }

    func releaseAll() {
        leases.values.forEach { $0.release() }
        leases.removeAll()
    }

    /// Runs [reload], which must request every visible cell before returning, then releases the
    /// leases it displaced.
    func deferringReleases(_ reload: () -> Void) {
        guard deferred == nil else { return reload() }
        deferred = []
        reload()
        let displaced = deferred ?? []
        deferred = nil
        displaced.forEach { $0.release() }
    }

    private func retire(_ lease: DeltaItemLease<T>) {
        if deferred != nil { deferred?.append(lease) } else { lease.release() }
    }
}

func acquireSectionedDeltaItem<T: AnyObject>(_ delta: AnyObject, section: Int, item: Int) -> DeltaItemLease<T>? {
    let selector = SectionedRowsSelector.acquireItem
    typealias Acquire = @convention(c) (AnyObject, Selector, Int32, Int32) -> AnyObject?
    guard let imp = DeltaIMPCache.shared.imp(for: delta, selector),
          let lease = unsafeBitCast(imp, to: Acquire.self)(delta, selector, Int32(section), Int32(item)) else { return nil }
    return deltaItemLease(lease)
}

private func loadedItemCountViaRuntime(_ delta: AnyObject, sectionIndex: Int32) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> Int32
    let selector = SectionedRowsSelector.loadedItemCount
    // Older frameworks lack the loaded count; their sections are read in full.
    guard let imp = DeltaIMPCache.shared.imp(for: delta, selector) else {
        return itemCountViaRuntime(delta, sectionIndex: sectionIndex)
    }
    return Int(unsafeBitCast(imp, to: Fn.self)(delta, selector, sectionIndex))
}

private func triggerLoadViaRuntime(_ delta: AnyObject, sectionIndex: Int32, itemIndex: Int32) {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32, Int32) -> Void
    let selector = SectionedRowsSelector.triggerLoad
    // Older frameworks can't page sections; their sections hold every row they have.
    guard let imp = DeltaIMPCache.shared.imp(for: delta, selector) else { return }
    unsafeBitCast(imp, to: Fn.self)(delta, selector, sectionIndex, itemIndex)
}

private func trackStableIdsViaRuntime(_ delta: AnyObject, previous: AnyObject?) -> AnyObject? {
    typealias Fn = @convention(c) (AnyObject, Selector, AnyObject?) -> AnyObject?
    let selector = SectionedRowsSelector.trackStableIds
    guard let imp = DeltaIMPCache.shared.imp(for: delta, selector) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(delta, selector, previous)
}

private func sectionIdViaRuntime(_ ids: AnyObject, sectionIndex: Int32) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> Int32
    let selector = SectionedRowsSelector.sectionId
    guard let imp = DeltaIMPCache.shared.imp(for: ids, selector) else { return Int(sectionIndex) }
    return Int(unsafeBitCast(imp, to: Fn.self)(ids, selector, sectionIndex))
}

private func itemIdViaRuntime(_ ids: AnyObject, sectionIndex: Int32, itemIndex: Int32) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32, Int32) -> Int32
    let selector = SectionedRowsSelector.itemId
    guard let imp = DeltaIMPCache.shared.imp(for: ids, selector) else { return Int(itemIndex) }
    return Int(unsafeBitCast(imp, to: Fn.self)(ids, selector, sectionIndex, itemIndex))
}

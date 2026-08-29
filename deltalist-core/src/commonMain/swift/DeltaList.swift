import SwiftUI
import Combine

// MARK: - Cross-module protocols
//
// A `Delta` / `SectionedDelta` vended by a consumer's KMP framework (which exports deltalist-core)
// is a DISTINCT Obj-C class from this framework's own `Delta` / `SectionedDelta`, so a direct
// `as? Delta<T>` cast fails across the framework boundary. An `as? DeltaProtocol` cast can't bridge
// that gap either — a Kotlin/Native class never declares conformance to a Swift `@objc` protocol —
// so the SwiftUI wrappers fall back to the runtime-selector helpers below, invoking the exported
// Obj-C accessor methods directly. These protocols remain for the typed/same-module path and the
// UIKit data sources in DeltaDataSource.swift; they are defined here (not under that file's
// `#if canImport(UIKit)`) so the SwiftUI wrappers can share them on every Apple platform.

/// Structural access to a `Delta` without coupling to its concrete (per-framework) type.
/// WARNING: never add `items` here — reading it bridges and force-loads the entire backing list.
@objc public protocol DeltaProtocol {
    var change: Change { get }
    func loadedItems() -> [Any]
    func totalSize() -> Int32
    func isLoadedAt(index: Int32) -> Bool
    func getLoadedItemAt(index: Int32) -> Any?
    func triggerLoadAt(index: Int32)
}

/// Structural access to a `SectionedDelta`'s change.
/// WARNING: the `sections` property bridges (and force-loads) the entire backing list; the
/// sectioned wrapper reads structure through the runtime-selector helpers below instead.
@objc public protocol SectionedDeltaProtocol {
    var change: SectionedChange { get }
    var sections: [Any] { get }
}

// MARK: - Cross-framework runtime selectors + IMP cache
//
// The runtime-selector path (the helpers at the bottom of this file, plus the UIKit data source and
// the notifier) invokes the exported Kotlin accessors by Obj-C selector. Selectors come from
// `#selector` against the @objc protocols that already declare these members — `DeltaProtocol` for
// the flat accessors and the selector-only `SectionedDeltaRuntimeABI` for the sectioned ones — so a
// rename of a bridged signature is a compile error instead of a silent runtime miss, and each
// selector is interned once at load rather than rebuilt from a String on every call.
//
// Resolved IMPs are cached per (class, selector): a `Delta` *instance* is replaced on every emission
// but its Obj-C *class* is stable, so the responds(to:)/class_getInstanceMethod walk runs once per
// class and a hot per-row call (isLoaded/loadedItem) becomes a dictionary hit plus a C call.

/// Selector-only mirror of the sectioned Kotlin accessors that aren't on `SectionedDeltaProtocol`.
/// Nothing conforms to it; it exists solely so `#selector` can derive checked selectors for them.
@objc private protocol SectionedDeltaRuntimeABI {
    func sectionCount() -> Int32
    func getHeaderAt(sectionIndex: Int32) -> Any?
    func getItemCountAt(sectionIndex: Int32) -> Int32
    func getItemAt(sectionIndex: Int32, itemIndex: Int32) -> Any?
}

/// Compile-time-checked selectors for the exported Kotlin `Delta` / `SectionedDelta` accessors.
enum DeltaSelector {
    static let loadedItems = #selector(DeltaProtocol.loadedItems)
    static let totalSize = #selector(DeltaProtocol.totalSize)
    static let change = #selector(getter: DeltaProtocol.change)
    static let isLoadedAt = #selector(DeltaProtocol.isLoadedAt(index:))
    static let getLoadedItemAt = #selector(DeltaProtocol.getLoadedItemAt(index:))
    static let triggerLoadAt = #selector(DeltaProtocol.triggerLoadAt(index:))
    static let sectionCount = #selector(SectionedDeltaRuntimeABI.sectionCount)
    static let getHeaderAt = #selector(SectionedDeltaRuntimeABI.getHeaderAt(sectionIndex:))
    static let getItemCountAt = #selector(SectionedDeltaRuntimeABI.getItemCountAt(sectionIndex:))
    static let getItemAt = #selector(SectionedDeltaRuntimeABI.getItemAt(sectionIndex:itemIndex:))
}

/// Caches resolved Obj-C IMPs keyed by (class, selector) for the cross-framework runtime path.
/// A nil entry records "this class does not respond", so negatives are cached too.
final class DeltaIMPCache: @unchecked Sendable {
    static let shared = DeltaIMPCache()

    private struct Key: Hashable {
        let cls: ObjectIdentifier
        let sel: Selector
    }
    private let lock = NSLock()
    private var cache: [Key: IMP?] = [:]

    func imp(for obj: AnyObject, _ sel: Selector) -> IMP? {
        let cls: AnyClass = type(of: obj)
        let key = Key(cls: ObjectIdentifier(cls), sel: sel)
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[key] { return cached }
        let resolved = obj.responds(to: sel)
            ? class_getInstanceMethod(cls, sel).map(method_getImplementation)
            : nil
        cache[key] = resolved
        return resolved
    }
}

// MARK: - DeltaList

/// Observable binding for a Kotlin `Flow<Delta<T>>`. A single wrapper that serves both
/// fully-loaded ("hard") lists and soft/paginated lists, preserving deltalist's core properties:
///
/// - **Laziness**: only ever reads `loadedItems()` / `totalSize()` — never `delta.items`, which
///   force-bridges (and force-loads) the ENTIRE backing list, catastrophic for soft lists. For a
///   soft list, drive `ForEach(0..<list.totalSize)` and resolve each row with `loadedItem(at:)`,
///   falling back to a placeholder that calls `triggerLoad(at:)` on appearance.
/// - **Stable identity**: items carry their own ids; always key `ForEach` by a stable id, never
///   by array position.
/// - **Diff-driven animation**: the source already computed the minimal diff. A non-`Reload`
///   `change` is applied inside `withAnimation` so SwiftUI's id-based reconcile animates; a
///   `Reload` snaps without animation (mirrors the UIKit data source).
///
/// Collect from a `.task`, which scopes the subscription to the view's lifetime and cancels
/// automatically — no manual `onAppear`/`onDisappear` bind/unbind:
/// ```swift
/// struct MyListView: View {
///     let viewModel = ListViewModel()
///     @StateObject private var list = DeltaList<Item>()
///
///     var body: some View {
///         List {
///             ForEach(list.loadedItems, id: \.stableId) { item in Row(item) }
///         }
///         .task { await list.collect(viewModel.items) }
///     }
/// }
/// ```
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public final class DeltaList<T: AnyObject>: ObservableObject {
    /// Currently-loaded items. For a hard list this is every item; for a soft list it is only the
    /// loaded subset, so prefer `totalSize` + `loadedItem(at:)` when rendering placeholders.
    @Published public private(set) var loadedItems: [T] = []

    /// Total logical size, including not-yet-loaded slots in a soft list. Equals
    /// `loadedItems.count` for a fully-loaded list.
    @Published public private(set) var totalSize: Int = 0

    /// Whether the most recent change should animate (a `Reload` should not).
    @Published public private(set) var animatesChanges: Bool = false

    /// Invoked if the observed flow terminates with an error. Defaults to nil (silent); set it to
    /// route upstream failures to telemetry.
    public var onError: ((Error) -> Void)?

    /// Retained so the soft-list accessors can query the live backing without re-bridging.
    private var currentDelta: AnyObject?

    public init() {}

    /// Collects a Kotlin `Flow<Delta<T>>` (a SKIE `AsyncSequence`) until the surrounding task is
    /// cancelled. Drive it from `.task { await list.collect(flow) }`.
    public func collect<S: AsyncSequence>(_ flow: S) async {
        do {
            for try await value in flow {
                if Task.isCancelled { break }
                applyValue(value as AnyObject)
            }
        } catch {
            onError?(error)
        }
    }

    // MARK: Soft-list access

    /// Whether the slot at `index` is loaded. Valid for any index in `0..<totalSize`.
    public func isLoaded(at index: Int) -> Bool {
        if let delta = currentDelta as? Delta<T> { return delta.isLoadedAt(index: Int32(index)) }
        if let delta = currentDelta as? Delta<AnyObject> { return delta.isLoadedAt(index: Int32(index)) }
        if let delta = currentDelta, DeltaIMPCache.shared.imp(for: delta, DeltaSelector.isLoadedAt) != nil {
            return isLoadedAtViaRuntime(delta, index: Int32(index))
        }
        return index < loadedItems.count
    }

    /// The item at `index`, or nil if that slot is not yet loaded.
    public func loadedItem(at index: Int) -> T? {
        if let delta = currentDelta as? Delta<T> { return delta.getLoadedItemAt(index: Int32(index)) as? T }
        if let delta = currentDelta as? Delta<AnyObject> { return delta.getLoadedItemAt(index: Int32(index)) as? T }
        if let delta = currentDelta, DeltaIMPCache.shared.imp(for: delta, DeltaSelector.getLoadedItemAt) != nil {
            return loadedItemAtViaRuntime(delta, index: Int32(index)) as? T
        }
        return index < loadedItems.count ? loadedItems[index] : nil
    }

    /// Requests that the soft list load the slot at `index`. Call from a placeholder row's
    /// `.onAppear`; the value arrives on a subsequent emission.
    public func triggerLoad(at index: Int) {
        if let delta = currentDelta as? Delta<T> { delta.triggerLoadAt(index: Int32(index)); return }
        if let delta = currentDelta as? Delta<AnyObject> { delta.triggerLoadAt(index: Int32(index)); return }
        if let delta = currentDelta, DeltaIMPCache.shared.imp(for: delta, DeltaSelector.triggerLoadAt) != nil {
            triggerLoadAtViaRuntime(delta, index: Int32(index))
        }
    }

    // MARK: Application

    /// Applies one delta emitted by an externally-owned collector. Generated ViewModel list
    /// bindings use this to keep stream ownership outside DeltaList while reusing this class's
    /// native extraction, soft-list, and animation behavior.
    public func apply(delta value: Any) {
        applyValue(value as AnyObject)
    }

    private func applyValue(_ value: AnyObject) {
        // Always read through loadedItems()/totalSize(): touching `delta.items` bridges and
        // force-loads the entire backing list, and `as!` crashes on erased/heterogeneous elements.
        // Cascade mirrors the sectioned wrapper: same-module cast, SKIE-erased cast, then
        // runtime-selector extraction for a consumer framework's own Delta class (a distinct
        // Obj-C class that the typed casts — and an `as? DeltaProtocol` cast, which a Kotlin
        // class never satisfies — all miss).
        if let typed = value as? Delta<T> {
            publish(delta: typed,
                    items: typed.loadedItems().compactMap { $0 as? T },
                    total: Int(typed.totalSize()),
                    isReload: typed.change is Change.Reload)
        } else if let erased = value as? Delta<AnyObject> {
            publish(delta: erased,
                    items: erased.loadedItems().compactMap { $0 as? T },
                    total: Int(erased.totalSize()),
                    isReload: erased.change is Change.Reload)
        } else {
            publish(delta: value,
                    items: loadedItemsViaRuntime(value).compactMap { $0 as? T },
                    total: totalSizeViaRuntime(value),
                    isReload: changeViaRuntime(value) is Change.Reload)
        }
    }

    private func publish(delta: AnyObject, items: [T], total: Int, isReload: Bool) {
        currentDelta = delta
        if isReload {
            animatesChanges = false
            loadedItems = items
            totalSize = total
        } else {
            withAnimation {
                animatesChanges = true
                loadedItems = items
                totalSize = total
            }
        }
    }
}

// MARK: - SectionedDeltaList

/// Observable binding for a Kotlin `Flow<SectionedDelta<H, T>>`.
///
/// Extraction goes exclusively through the Kotlin accessor methods
/// (`sectionCount()` / `getHeaderAt(sectionIndex:)` / `getItemCountAt(sectionIndex:)` /
/// `getItemAt(sectionIndex:itemIndex:)`). It NEVER reads `delta.sections` or `section.items`,
/// either of which bridges (and force-loads) the entire backing list. Key the section `ForEach`
/// by a stable header id and each item `ForEach` by a stable item id.
///
/// ```swift
/// @StateObject private var list = SectionedDeltaList<Header, Item>()
/// // ...
/// List {
///     ForEach(list.sections, id: \.headerId) { section in
///         Section(header: HeaderView(section.header)) {
///             ForEach(section.items, id: \.itemId) { item in Row(item) }
///         }
///     }
/// }
/// .task { await list.collect(viewModel.sections) }
/// ```
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public final class SectionedDeltaList<H: AnyObject, T: AnyObject>: ObservableObject {
    /// One extracted section: its header and loaded items, both raw Kotlin types.
    public struct Section {
        public let header: H
        public let items: [T]
        public init(header: H, items: [T]) {
            self.header = header
            self.items = items
        }
    }

    @Published public private(set) var sections: [Section] = []

    /// Whether the most recent change should animate (a `Reload` should not).
    @Published public private(set) var animatesChanges: Bool = false

    /// Invoked if the observed flow terminates with an error. Defaults to nil (silent).
    public var onError: ((Error) -> Void)?

    public init() {}

    public func collect<S: AsyncSequence>(_ flow: S) async {
        do {
            for try await value in flow {
                if Task.isCancelled { break }
                apply(value as AnyObject)
            }
        } catch {
            onError?(error)
        }
    }

    private func apply(_ value: AnyObject) {
        // Cascade mirrors the UIKit sectioned data source: same-module cast, SKIE-erased cast,
        // then runtime-selector extraction for a consumer framework's own SectionedDelta class.
        if let typed = value as? SectionedDelta<H, T> {
            publish(extract(typed), isReload: typed.change is SectionedChange.Reload)
        } else if let erased = value as? SectionedDelta<AnyObject, AnyObject> {
            publish(extractErased(erased), isReload: erased.change is SectionedChange.Reload)
        } else {
            publish(extractViaRuntime(value), isReload: sectionedChangeViaRuntime(value) is SectionedChange.Reload)
        }
    }

    private func extract(_ delta: SectionedDelta<H, T>) -> [Section] {
        let count = Int(delta.sectionCount())
        var result: [Section] = []
        result.reserveCapacity(count)
        for s in 0..<count {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(s)) as? H else { continue }
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(s)))
            var items: [T] = []
            items.reserveCapacity(itemCount)
            for i in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(s), itemIndex: Int32(i)) as? T {
                    items.append(item)
                }
            }
            result.append(Section(header: header, items: items))
        }
        return result
    }

    private func extractErased(_ delta: SectionedDelta<AnyObject, AnyObject>) -> [Section] {
        let count = Int(delta.sectionCount())
        var result: [Section] = []
        result.reserveCapacity(count)
        for s in 0..<count {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(s)) as? H else { continue }
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(s)))
            var items: [T] = []
            items.reserveCapacity(itemCount)
            for i in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(s), itemIndex: Int32(i)) as? T {
                    items.append(item)
                }
            }
            result.append(Section(header: header, items: items))
        }
        return result
    }

    private func extractViaRuntime(_ delta: AnyObject) -> [Section] {
        let count = sectionCountViaRuntime(delta)
        var result: [Section] = []
        result.reserveCapacity(count)
        for s in 0..<count {
            guard let header = headerViaRuntime(delta, sectionIndex: Int32(s)) as? H else { continue }
            let itemCount = itemCountViaRuntime(delta, sectionIndex: Int32(s))
            var items: [T] = []
            items.reserveCapacity(itemCount)
            for i in 0..<itemCount {
                if let item = itemViaRuntime(delta, sectionIndex: Int32(s), itemIndex: Int32(i)) as? T {
                    items.append(item)
                }
            }
            result.append(Section(header: header, items: items))
        }
        return result
    }

    private func publish(_ newSections: [Section], isReload: Bool) {
        if isReload {
            animatesChanges = false
            sections = newSections
        } else {
            withAnimation {
                animatesChanges = true
                sections = newSections
            }
        }
    }
}

// MARK: - Flat runtime-selector helpers
//
// Used when both the same-module and SKIE-erased generic casts fail (a consumer framework's own
// `Delta` class — a distinct Obj-C class an `as? DeltaProtocol` cast can't reach, since a Kotlin
// class never declares conformance to a Swift `@objc` protocol). The Kotlin extension accessors
// are exported as Obj-C instance methods (a `Delta (Extensions)` category), so they can be invoked
// directly by selector without ever bridging `delta.items`. Mirrors the sectioned helpers below.

private func loadedItemsViaRuntime(_ obj: AnyObject) -> [Any] {
    typealias Fn = @convention(c) (AnyObject, Selector) -> NSArray?
    let sel = DeltaSelector.loadedItems
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return [] }
    return (unsafeBitCast(imp, to: Fn.self)(obj, sel) as? [Any]) ?? []
}

private func totalSizeViaRuntime(_ obj: AnyObject) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector) -> Int32
    let sel = DeltaSelector.totalSize
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
    return Int(unsafeBitCast(imp, to: Fn.self)(obj, sel))
}

private func changeViaRuntime(_ obj: AnyObject) -> AnyObject? {
    typealias Fn = @convention(c) (AnyObject, Selector) -> AnyObject?
    let sel = DeltaSelector.change
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel)
}

private func isLoadedAtViaRuntime(_ obj: AnyObject, index: Int32) -> Bool {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> Bool
    let sel = DeltaSelector.isLoadedAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return false }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel, index)
}

private func loadedItemAtViaRuntime(_ obj: AnyObject, index: Int32) -> Any? {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
    let sel = DeltaSelector.getLoadedItemAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel, index)
}

private func triggerLoadAtViaRuntime(_ obj: AnyObject, index: Int32) {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> Void
    let sel = DeltaSelector.triggerLoadAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return }
    unsafeBitCast(imp, to: Fn.self)(obj, sel, index)
}

// MARK: - Sectioned runtime-selector helpers
//
// Used only when both the same-module and SKIE-erased generic casts fail (a consumer framework's
// own SectionedDelta class). Call the exported Kotlin accessor methods directly by selector so the
// backing list is never bridged. Mirrors the private helpers in the UIKit sectioned data source.

private func sectionCountViaRuntime(_ obj: AnyObject) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector) -> Int32
    let sel = DeltaSelector.sectionCount
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
    return Int(unsafeBitCast(imp, to: Fn.self)(obj, sel))
}

private func sectionedChangeViaRuntime(_ obj: AnyObject) -> AnyObject? {
    typealias Fn = @convention(c) (AnyObject, Selector) -> AnyObject?
    let sel = DeltaSelector.change
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel)
}

private func headerViaRuntime(_ obj: AnyObject, sectionIndex: Int32) -> Any? {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
    let sel = DeltaSelector.getHeaderAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel, sectionIndex)
}

private func itemCountViaRuntime(_ obj: AnyObject, sectionIndex: Int32) -> Int {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32) -> Int32
    let sel = DeltaSelector.getItemCountAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return 0 }
    return Int(unsafeBitCast(imp, to: Fn.self)(obj, sel, sectionIndex))
}

private func itemViaRuntime(_ obj: AnyObject, sectionIndex: Int32, itemIndex: Int32) -> Any? {
    typealias Fn = @convention(c) (AnyObject, Selector, Int32, Int32) -> AnyObject?
    let sel = DeltaSelector.getItemAt
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return nil }
    return unsafeBitCast(imp, to: Fn.self)(obj, sel, sectionIndex, itemIndex)
}

import Foundation
import SwiftUI
#if canImport(UIKit) && !os(watchOS)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A direct, generated binding between a Kotlin delta stream and its Apple row representation.
///
/// Semantic list operations (filtering, mapping, sorting, grouping) deliberately do not exist on
/// this type. They belong in the ViewModel that produces the source stream. The classifier below is
/// only the platform adaptation from a raw Kotlin child ViewModel to its generated Apple wrapper.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public final class ViewModelListBinding<Raw: AnyObject, Element> {
    public typealias Classifier = @MainActor (Raw) -> Element
    public typealias Identity = @MainActor (Element) -> AnyHashable
    public typealias Observer = @MainActor (Element) async -> Void

    private let collectSource: @MainActor (
        @escaping @MainActor (Any) -> Void,
        @escaping @MainActor (Error) -> Void
    ) async -> Void
    private let classifier: Classifier
    private let identity: Identity
    private let observer: Observer
    private var elementCache: [ObjectIdentifier: Element] = [:]

    /// Receives a SKIE `AsyncSequence` directly. Its concrete type stays captured here rather than
    /// leaking through the public binding type, which is what lets generated properties have a
    /// stable `ViewModelListBinding<Raw, Element>` signature.
    ///
    /// This is `public` rather than `@_spi` on purpose: generated bindings live in a separate Swift
    /// module that consumes the framework as a binary XCFramework, and `xcodebuild
    /// -create-xcframework` drops the `.private.swiftinterface` when it merges the two simulator
    /// architectures into one slice — so an `@_spi` initializer is invisible to codegen on simulator
    /// builds. Keeping it public is the only reliable way for generated code to construct a binding
    /// across that boundary. Application code should still bind through the generated wrappers, never
    /// this initializer directly.
    public init<Source: AsyncSequence>(
        source: Source,
        classify: @escaping Classifier,
        identity: @escaping Identity,
        observe: @escaping Observer = { _ in }
    ) {
        self.classifier = classify
        self.identity = identity
        self.observer = observe
        self.collectSource = { receive, fail in
            guard !Task.isCancelled else { return }
            do {
                for try await delta in source {
                    if Task.isCancelled { break }
                    receive(delta)
                }
            } catch {
                if !Task.isCancelled && !(error is CancellationError) { fail(error) }
            }
        }
    }

    /// Returns a stable wrapper for the lifetime of a raw child object. This prevents SwiftUI from
    /// losing row observation state merely because the parent list publishes another delta.
    public func element(for raw: Raw) -> Element {
        let key = ObjectIdentifier(raw)
        if let cached = elementCache[key] { return cached }
        let element = classifier(raw)
        elementCache[key] = element
        return element
    }

    /// Drops wrappers for children no longer present in the currently materialized list.
    func retainCachedElements(for rawItems: [Raw]) {
        let live = Set(rawItems.map(ObjectIdentifier.init))
        elementCache = elementCache.filter { live.contains($0.key) }
    }

    /// Creates a wrapper without retaining it in the SwiftUI identity cache. Collection-view cells
    /// own their wrappers, so off-screen rows can release their state subscription naturally.
    public func makeElement(for raw: Raw) -> Element {
        classifier(raw)
    }

    public func id(for element: Element) -> AnyHashable {
        identity(element)
    }

    /// Observes one generated child wrapper for the lifetime of its mounted SwiftUI row.
    public func observe(_ element: Element) async {
        await observer(element)
    }

    /// Feeds the native DeltaList SwiftUI store without exposing the raw stream to application code.
    public func collect(into list: DeltaList<Raw>) async {
        guard !Task.isCancelled else { return }
        await collectSource(
            { delta in list.apply(delta: delta) },
            { error in list.onError?(error) }
        )
    }

    #if canImport(UIKit) && !os(watchOS)
    /// Feeds the existing UIKit delta data source. Mutation application remains wholly owned by
    /// `DeltaCollectionDataSource`.
    @available(iOS 15.0, *)
    public func bind(to dataSource: DeltaCollectionDataSource<Raw>) {
        let task = Task { @MainActor [collectSource, weak dataSource] in
            await collectSource(
                { [weak dataSource] delta in dataSource?.apply(delta: delta) },
                { [weak dataSource] error in dataSource?.onError?(error) }
            )
        }
        dataSource.setBindingTask(task)
    }
    #elseif canImport(AppKit)
    /// Feeds the existing AppKit delta data source. Mutation application remains wholly owned by
    /// `DeltaNSCollectionDataSource`.
    @available(macOS 12.0, *)
    public func bind(to dataSource: DeltaNSCollectionDataSource<Raw>) {
        let task = Task { @MainActor [collectSource, weak dataSource] in
            await collectSource(
                { [weak dataSource] delta in dataSource?.apply(delta: delta) },
                { [weak dataSource] error in dataSource?.onError?(error) }
            )
        }
        dataSource.setBindingTask(task)
    }
    #endif
}

// MARK: - SwiftUI

/// A one-expression SwiftUI `List` backed by a fully-loaded generated ViewModel list binding.
/// Collection and per-row state observation automatically follow the mounted view lifecycle. Use
/// `DeltaLazyListView` when the source has unloaded soft-list slots.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaListView<Raw: AnyObject, Element, RowContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    @StateObject private var list = DeltaList<Raw>(materializesItems: false)

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.rowContent = rowContent
    }

    public var body: some View {
        List {
            DeltaForEachRows(binding: binding, list: list, rowContent: rowContent)
        }
        .task(id: ObjectIdentifier(binding)) { await binding.collect(into: list) }
    }
}

/// The embeddable peer of `DeltaListView`: it owns collection and row observation but contributes
/// only a `ForEach`, allowing a bound list inside stacks, grids, menus, or custom containers.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaForEach<Raw: AnyObject, Element, RowContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    @StateObject private var list = DeltaList<Raw>(materializesItems: false)
    private var suppliedList: DeltaList<Raw>? = nil

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.rowContent = rowContent
    }

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        observing list: DeltaList<Raw>,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.suppliedList = list
        self.rowContent = rowContent
    }

    public var body: some View {
        DeltaForEachRows(binding: binding, list: suppliedList ?? list, rowContent: rowContent)
            .task(id: ObjectIdentifier(binding)) {
                if suppliedList == nil { await binding.collect(into: list) }
            }
    }
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
private struct DeltaForEachRows<Raw: AnyObject, Element, RowContent: View>: View {
    let binding: ViewModelListBinding<Raw, Element>
    @ObservedObject var list: DeltaList<Raw>
    let rowContent: (Element) -> RowContent

    var body: some View {
        ForEach(loadedSlots(binding: binding, list: list)) { slot in
            DeltaBoundRow(binding: binding, list: list, index: slot.index, rowContent: rowContent)
        }
    }
}

private struct ViewModelListSlot: Identifiable {
    let id: AnyHashable
    let index: Int
    let loaded: Bool
}

private struct ViewModelUnloadedSlotIdentity: Hashable { let index: Int }
private struct RowObservationIdentity: Hashable {
    let binding: ObjectIdentifier
    let raw: ObjectIdentifier
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
private func loadedSlots<Raw: AnyObject, Element>(
    binding: ViewModelListBinding<Raw, Element>, list: DeltaList<Raw>, includeUnloaded: Bool = false
) -> [ViewModelListSlot] {
    (0..<list.totalSize).compactMap { index in
        if let raw = list.loadedItem(at: index) {
            // Classify for identity only. The mounted row owns the retained wrapper and lease.
            return ViewModelListSlot(id: binding.id(for: binding.makeElement(for: raw)), index: index, loaded: true)
        }
        return includeUnloaded ? ViewModelListSlot(
            id: AnyHashable(ViewModelUnloadedSlotIdentity(index: index)), index: index, loaded: false) : nil
    }
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
private final class BoundRowState<Raw: AnyObject, Element>: ObservableObject {
    struct Value {
        let element: Element
        let lease: DeltaItemLease<Raw>
        let binding: ViewModelListBinding<Raw, Element>
    }
    @Published var value: Value?

    func update(binding: ViewModelListBinding<Raw, Element>, list: DeltaList<Raw>, index: Int) {
        guard let next = list.acquireItem(at: index) else { clear(); return }
        let previous = value
        let element: Element
        if let previous, previous.lease.item === next.item, previous.binding === binding {
            element = previous.element
        } else {
            element = binding.makeElement(for: next.item)
        }
        value = Value(element: element, lease: next, binding: binding)
        previous?.lease.release()
    }
    func clear() {
        let previous = value
        value = nil
        previous?.lease.release()
    }
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
private struct BoundRowInput<Raw: AnyObject, Element>: Equatable {
    let binding: ViewModelListBinding<Raw, Element>
    let list: DeltaList<Raw>
    let index: Int
    let revision: UInt

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.binding === rhs.binding && lhs.list === rhs.list && lhs.index == rhs.index && lhs.revision == rhs.revision
    }
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
private struct DeltaBoundRow<Raw: AnyObject, Element, RowContent: View>: View {
    let binding: ViewModelListBinding<Raw, Element>
    @ObservedObject var list: DeltaList<Raw>
    let index: Int
    let rowContent: (Element) -> RowContent
    @StateObject private var state = BoundRowState<Raw, Element>()

    var body: some View {
        let input = BoundRowInput(binding: binding, list: list, index: index, revision: list.revision)
        ZStack(alignment: .leading) {
            if let value = state.value {
                rowContent(value.element)
                    .task(id: RowObservationIdentity(binding: ObjectIdentifier(value.binding), raw: ObjectIdentifier(value.lease.item))) {
                        await value.binding.observe(value.element)
                    }
            } else if let raw = list.loadedItem(at: index) {
                // A real row mounts the lifecycle before the first acquisition.
                rowContent(binding.makeElement(for: raw))
            }
        }
        .onAppear { state.update(binding: binding, list: list, index: index) }
        .onChange(of: input) { next in
            state.update(binding: next.binding, list: next.list, index: next.index)
        }
        .onDisappear { state.clear() }
    }
}

/// A paginated SwiftUI list. Only mounted rows retain acquired children and observe their state.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaLazyListView<Raw: AnyObject, Element, RowContent: View, LoadingContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    private let loadingContent: (Int) -> LoadingContent
    @StateObject private var list = DeltaList<Raw>(materializesItems: false)

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder loading: @escaping (Int) -> LoadingContent,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.loadingContent = loading
        self.rowContent = rowContent
    }

    public var body: some View {
        List(loadedSlots(binding: binding, list: list, includeUnloaded: true)) { slot in
            if slot.loaded {
                DeltaBoundRow(binding: binding, list: list, index: slot.index, rowContent: rowContent)
            } else {
                loadingContent(slot.index)
                    .onAppear { list.triggerLoad(at: slot.index) }
            }
        }
        .task(id: ObjectIdentifier(binding)) { await binding.collect(into: list) }
    }
}

#if canImport(UIKit) && !os(watchOS)
/// Reusable heterogeneous UIKit cell routing for one exact generated list element type.
@available(iOS 15.0, *)
@MainActor
public protocol DeltaUICollectionViewCellMap {
    associatedtype Element
    static func register(in collectionView: UICollectionView)
    static func cell(
        in collectionView: UICollectionView,
        at indexPath: IndexPath,
        for element: Element
    ) -> UICollectionViewCell
}

/// Homogeneous cells can adopt this protocol and use the `cell:` one-line binding overload.
@available(iOS 15.0, *)
@MainActor
public protocol DeltaUICollectionViewCell: AnyObject {
    associatedtype Element
    func bind(_ element: Element)
}

private let viewModelListUIKitDataSourceKey = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)

@available(iOS 15.0, *)
extension UICollectionView {
    @discardableResult
    public func items<Raw, Element, CellMap>(
        _ binding: ViewModelListBinding<Raw, Element>,
        using cellMap: CellMap.Type
    ) -> DeltaCollectionDataSource<Raw>
    where Raw: AnyObject, CellMap: DeltaUICollectionViewCellMap, CellMap.Element == Element {
        CellMap.register(in: self)
        let dataSource = DeltaCollectionDataSource<Raw>(collectionView: self) { cv, indexPath, raw in
            CellMap.cell(in: cv, at: indexPath, for: binding.makeElement(for: raw))
        }
        objc_setAssociatedObject(self, viewModelListUIKitDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        binding.bind(to: dataSource)
        return dataSource
    }

    @discardableResult
    public func items<Raw, Cell>(
        _ binding: ViewModelListBinding<Raw, Cell.Element>,
        cell cellType: Cell.Type
    ) -> DeltaCollectionDataSource<Raw>
    where Raw: AnyObject, Cell: UICollectionViewCell & DeltaUICollectionViewCell {
        let reuseId = String(describing: Cell.self)
        register(Cell.self, forCellWithReuseIdentifier: reuseId)
        let dataSource = DeltaCollectionDataSource<Raw>(collectionView: self) { cv, indexPath, raw in
            let cell = cv.dequeueReusableCell(withReuseIdentifier: reuseId, for: indexPath) as! Cell
            cell.bind(binding.makeElement(for: raw))
            return cell
        }
        objc_setAssociatedObject(self, viewModelListUIKitDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        binding.bind(to: dataSource)
        return dataSource
    }
}

#elseif canImport(AppKit)
/// Reusable heterogeneous AppKit item routing for one exact generated list element type.
@available(macOS 12.0, *)
@MainActor
public protocol DeltaNSCollectionViewItemMap {
    associatedtype Element
    static func register(in collectionView: NSCollectionView)
    static func item(
        in collectionView: NSCollectionView,
        at indexPath: IndexPath,
        for element: Element
    ) -> NSCollectionViewItem
}

@available(macOS 12.0, *)
@MainActor
public protocol DeltaNSCollectionViewBindableItem: AnyObject {
    associatedtype Element
    func bind(_ element: Element)
}

private let viewModelListAppKitDataSourceKey = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)

@available(macOS 12.0, *)
extension NSCollectionView {
    @discardableResult
    public func items<Raw, Element, ItemMap>(
        _ binding: ViewModelListBinding<Raw, Element>,
        using itemMap: ItemMap.Type
    ) -> DeltaNSCollectionDataSource<Raw>
    where Raw: AnyObject, ItemMap: DeltaNSCollectionViewItemMap, ItemMap.Element == Element {
        ItemMap.register(in: self)
        let dataSource = DeltaNSCollectionDataSource<Raw>(collectionView: self) { cv, indexPath, raw in
            ItemMap.item(in: cv, at: indexPath, for: binding.makeElement(for: raw))
        }
        objc_setAssociatedObject(self, viewModelListAppKitDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        binding.bind(to: dataSource)
        return dataSource
    }

    @discardableResult
    public func items<Raw, Item>(
        _ binding: ViewModelListBinding<Raw, Item.Element>,
        item itemType: Item.Type
    ) -> DeltaNSCollectionDataSource<Raw>
    where Raw: AnyObject, Item: NSCollectionViewItem & DeltaNSCollectionViewBindableItem {
        let identifier = NSUserInterfaceItemIdentifier(String(describing: Item.self))
        register(Item.self, forItemWithIdentifier: identifier)
        let dataSource = DeltaNSCollectionDataSource<Raw>(collectionView: self) { cv, indexPath, raw in
            let item = cv.makeItem(withIdentifier: identifier, for: indexPath) as! Item
            item.bind(binding.makeElement(for: raw))
            return item
        }
        objc_setAssociatedObject(self, viewModelListAppKitDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        binding.bind(to: dataSource)
        return dataSource
    }
}
#endif

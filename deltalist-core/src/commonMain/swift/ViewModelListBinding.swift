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

    private let startSource: @MainActor (
        @escaping @MainActor (Any) -> Void,
        @escaping @MainActor (Error) -> Void
    ) -> Task<Void, Never>
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
        self.startSource = { receive, fail in
            Task { @MainActor in
                do {
                    for try await delta in source {
                        if Task.isCancelled { break }
                        receive(delta)
                    }
                } catch {
                    fail(error)
                }
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
        let task = startSource(
            { delta in list.apply(delta: delta) },
            { error in list.onError?(error) }
        )
        await task.value
    }

    #if canImport(UIKit) && !os(watchOS)
    /// Feeds the existing UIKit delta data source. Mutation application remains wholly owned by
    /// `DeltaCollectionDataSource`.
    @available(iOS 15.0, *)
    public func bind(to dataSource: DeltaCollectionDataSource<Raw>) {
        let task = startSource(
            { delta in dataSource.apply(delta: delta) },
            { error in dataSource.onError?(error) }
        )
        dataSource.setBindingTask(task)
    }
    #elseif canImport(AppKit)
    /// Feeds the existing AppKit delta data source. Mutation application remains wholly owned by
    /// `DeltaNSCollectionDataSource`.
    @available(macOS 12.0, *)
    public func bind(to dataSource: DeltaNSCollectionDataSource<Raw>) {
        let task = startSource(
            { delta in dataSource.apply(delta: delta) },
            { error in dataSource.onError?(error) }
        )
        dataSource.setBindingTask(task)
    }
    #endif
}

// MARK: - SwiftUI

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
private struct ViewModelListRow<Element>: Identifiable {
    let id: AnyHashable
    let observationId: AnyHashable
    let element: Element
}

/// A one-expression SwiftUI `List` backed by a fully-loaded generated ViewModel list binding.
/// Collection and per-row state observation automatically follow the mounted view lifecycle. Use
/// `DeltaLazyListView` when the source has unloaded soft-list slots.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaListView<Raw: AnyObject, Element, RowContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    @StateObject private var list = DeltaList<Raw>()

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.rowContent = rowContent
    }

    private var rows: [ViewModelListRow<Element>] {
        binding.retainCachedElements(for: list.loadedItems)
        return list.loadedItems.map { raw in
            let element = binding.element(for: raw)
            return ViewModelListRow(
                id: binding.id(for: element),
                observationId: AnyHashable(ObjectIdentifier(raw)),
                element: element
            )
        }
    }

    public var body: some View {
        List(rows) { row in
            rowContent(row.element)
                .task(id: row.observationId) { await binding.observe(row.element) }
        }
        .task { await binding.collect(into: list) }
    }
}

/// The embeddable peer of `DeltaListView`: it owns collection and row observation but contributes
/// only a `ForEach`, allowing a bound list inside stacks, grids, menus, or custom containers.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaForEach<Raw: AnyObject, Element, RowContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    @StateObject private var list = DeltaList<Raw>()

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.rowContent = rowContent
    }

    private var rows: [ViewModelListRow<Element>] {
        binding.retainCachedElements(for: list.loadedItems)
        return list.loadedItems.map { raw in
            let element = binding.element(for: raw)
            return ViewModelListRow(
                id: binding.id(for: element),
                observationId: AnyHashable(ObjectIdentifier(raw)),
                element: element
            )
        }
    }

    public var body: some View {
        ForEach(rows) { row in
            rowContent(row.element)
                .task(id: row.observationId) { await binding.observe(row.element) }
        }
        .task { await binding.collect(into: list) }
    }
}

@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
private struct ViewModelListSlot<Element>: Identifiable {
    let id: AnyHashable
    let observationId: AnyHashable?
    let index: Int
    let element: Element?
}

private struct ViewModelUnloadedSlotIdentity: Hashable {
    let index: Int
}

/// Soft-list SwiftUI binding. Loaded slots use generated stable identity; unloaded slots use a
/// temporary positional identity and trigger their native DeltaList load when they appear.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct DeltaLazyListView<Raw: AnyObject, Element, RowContent: View, LoadingContent: View>: View {
    private let binding: ViewModelListBinding<Raw, Element>
    private let rowContent: (Element) -> RowContent
    private let loadingContent: (Int) -> LoadingContent
    @StateObject private var list = DeltaList<Raw>()

    public init(
        _ binding: ViewModelListBinding<Raw, Element>,
        @ViewBuilder loading: @escaping (Int) -> LoadingContent,
        @ViewBuilder rowContent: @escaping (Element) -> RowContent
    ) {
        self.binding = binding
        self.loadingContent = loading
        self.rowContent = rowContent
    }

    private var slots: [ViewModelListSlot<Element>] {
        binding.retainCachedElements(for: list.loadedItems)
        return (0..<list.totalSize).map { index in
            guard let raw = list.loadedItem(at: index) else {
                return ViewModelListSlot(
                    id: AnyHashable(ViewModelUnloadedSlotIdentity(index: index)),
                    observationId: nil,
                    index: index,
                    element: nil
                )
            }
            let element = binding.element(for: raw)
            return ViewModelListSlot(
                id: binding.id(for: element),
                observationId: AnyHashable(ObjectIdentifier(raw)),
                index: index,
                element: element
            )
        }
    }

    public var body: some View {
        List(slots) { slot in
            if let element = slot.element, let observationId = slot.observationId {
                rowContent(element)
                    .task(id: observationId) { await binding.observe(element) }
            } else {
                loadingContent(slot.index)
                    .onAppear { list.triggerLoad(at: slot.index) }
            }
        }
        .task { await binding.collect(into: list) }
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

#if canImport(AppKit) && !canImport(UIKit)
import AppKit
import ObjectiveC

// MARK: - DeltaRows (AppKit): typed registration + type-based item differentiation
//
// AppKit peer of DeltaRows.swift (UIKit). A `Row` / `Header` spec pairs an item (view-model) type
// with an `NSCollectionViewItem` / `NSView` class in one declaration; binding a stream registers
// every item, dispatches items to the first matching spec, drives per-row state via the
// app-installed `DeltaRowBinding.stateProvider`, and forwards selection.
//
// ```swift
// collectionView.sections(viewModel.sections) {
//     Header<SectionHeader, SectionHeaderView> { view, header in ... }
//     Row<Item, ItemCollectionViewItem> { item, model in ... }
// }
// ```

// MARK: - Spec protocol

/// Type-erased registration spec produced by `Row` / `Header`. First matching spec wins, so order
/// specs most-specific-first; a `Row<AnyObject, _>` / `Header<AnyObject, _>` acts as a trailing
/// fallback.
@MainActor
public protocol DeltaRowSpec {
    var reuseId: String { get }
    var isHeader: Bool { get }
    func register(in collectionView: NSCollectionView)
    func matches(_ item: AnyObject) -> Bool
    /// `target` is the `NSCollectionViewItem` for rows, or the supplementary `NSView` for headers.
    func configure(target: AnyObject, item: AnyObject)
    /// Returns true if this spec had an explicit selection handler and consumed the event.
    func select(item: NSCollectionViewItem, model: AnyObject) -> Bool
}

// MARK: - Row

/// Pairs an item type with an `NSCollectionViewItem` class. The reuse identifier defaults to the
/// item class name, and the item is registered automatically at bind time.
///
/// `VM` is intentionally unconstrained so Kotlin-interface protocols can be used as existentials;
/// matching is a runtime `item is VM` check.
public struct Row<VM, Item: NSCollectionViewItem>: DeltaRowSpec {
    public let reuseId: String
    public var isHeader: Bool { false }

    private let configureClosure: (@MainActor (Item, VM) -> Void)?
    private var selectClosure: (@MainActor (Item, VM) -> Void)?

    public init(
        id: String = String(describing: Item.self),
        configure: (@MainActor (Item, VM) -> Void)? = nil
    ) {
        self.reuseId = id
        self.configureClosure = configure
    }

    /// Typed selection handler. Without one, selection auto-forwards to the item's
    /// `ItemViewSelected.onSelected(_:)` if it conforms.
    public func onSelect(_ handler: @escaping @MainActor (Item, VM) -> Void) -> Self {
        var copy = self
        copy.selectClosure = handler
        return copy
    }

    public func register(in collectionView: NSCollectionView) {
        collectionView.register(Item.self, forItemWithIdentifier: NSUserInterfaceItemIdentifier(reuseId))
    }

    public func matches(_ item: AnyObject) -> Bool {
        return item is VM
    }

    public func configure(target: AnyObject, item: AnyObject) {
        guard let cell = target as? Item, let vm = item as? VM else { return }
        configureClosure?(cell, vm)
    }

    public func select(item: NSCollectionViewItem, model: AnyObject) -> Bool {
        guard let handler = selectClosure, let typedItem = item as? Item, let vm = model as? VM else {
            return false
        }
        handler(typedItem, vm)
        return true
    }
}

// MARK: - Header

/// Pairs a section-header type with a supplementary view class (section headers only).
public struct Header<H, View: NSView>: DeltaRowSpec {
    public let reuseId: String
    public var isHeader: Bool { true }

    private let configureClosure: (@MainActor (View, H) -> Void)?

    public init(
        id: String = String(describing: View.self),
        configure: (@MainActor (View, H) -> Void)? = nil
    ) {
        self.reuseId = id
        self.configureClosure = configure
    }

    public func register(in collectionView: NSCollectionView) {
        collectionView.register(
            View.self,
            forSupplementaryViewOfKind: NSCollectionView.elementKindSectionHeader,
            withIdentifier: NSUserInterfaceItemIdentifier(reuseId)
        )
    }

    public func matches(_ item: AnyObject) -> Bool {
        return item is H
    }

    public func configure(target: AnyObject, item: AnyObject) {
        guard let headerView = target as? View, let header = item as? H else { return }
        configureClosure?(headerView, header)
    }

    public func select(item: NSCollectionViewItem, model: AnyObject) -> Bool {
        return false
    }
}

// MARK: - Result builder

@resultBuilder
public enum DeltaRowsBuilder {
    public static func buildExpression(_ spec: any DeltaRowSpec) -> [any DeltaRowSpec] { [spec] }
    public static func buildBlock(_ specs: [any DeltaRowSpec]...) -> [any DeltaRowSpec] { specs.flatMap { $0 } }
    public static func buildOptional(_ specs: [any DeltaRowSpec]?) -> [any DeltaRowSpec] { specs ?? [] }
    public static func buildEither(first: [any DeltaRowSpec]) -> [any DeltaRowSpec] { first }
    public static func buildEither(second: [any DeltaRowSpec]) -> [any DeltaRowSpec] { second }
    public static func buildArray(_ specs: [[any DeltaRowSpec]]) -> [any DeltaRowSpec] { specs.flatMap { $0 } }
}

// MARK: - Item binding protocols

/// `NSCollectionViewItem`s adopt this to receive their row item and its state emissions. State
/// delivery requires an installed `DeltaRowBinding.stateProvider`.
@objc public protocol ViewModelBoundCell: AnyObject {
    @objc optional func viewModelDidChange(_ viewModel: AnyObject)
    @objc optional func viewModelStateDidChange(_ state: Any)
}

/// Items adopt this to receive selection when the matched `Row` has no explicit `.onSelect`.
public protocol ItemViewSelected {
    func onSelected(_ model: AnyObject)
}

// MARK: - State binding hook

/// deltalist cannot collect a consumer framework's ViewModel state flows itself (Flow interop is
/// per-framework), so the app installs this adapter once at startup. Given a row item it emits the
/// initial + subsequent states via `emit` and returns a cancel closure, or nil if the item has no
/// observable state.
public enum DeltaRowBinding {
    @MainActor public static var stateProvider: ((AnyObject, @escaping (Any) -> Void) -> (() -> Void)?)?
}

// MARK: - Per-binding state observation

/// Owns the per-row state observations for one bound data source. Observation follows visibility:
/// bound at item provide, cancelled when the item scrolls off (didEndDisplaying), replaced when an
/// item is reused for a different model, and torn down with the data source.
@available(macOS 11.0, *)
@MainActor
final class DeltaRowStateStore {
    private var cancels: [ObjectIdentifier: () -> Void] = [:]
    private var itemForCell: [ObjectIdentifier: ObjectIdentifier] = [:]
    private var cellForItem: [ObjectIdentifier: ObjectIdentifier] = [:]

    func bind(cell: NSCollectionViewItem, item: AnyObject) {
        (cell as? ViewModelBoundCell)?.viewModelDidChange?(item)

        let cellKey = ObjectIdentifier(cell)
        let itemKey = ObjectIdentifier(item)

        // Item reused for a different model: stop observing the model it used to show.
        if let previousItem = itemForCell[cellKey], previousItem != itemKey, cellForItem[previousItem] == cellKey {
            cancels.removeValue(forKey: previousItem)?()
            cellForItem.removeValue(forKey: previousItem)
        }
        itemForCell[cellKey] = itemKey
        cellForItem[itemKey] = cellKey

        guard let provider = DeltaRowBinding.stateProvider else { return }

        // Rebinding the same model (e.g. an Update mutation) replaces its observation.
        cancels.removeValue(forKey: itemKey)?()
        cancels[itemKey] = provider(item) { [weak self, weak cell] state in
            // Drop emissions once the item shows a different model.
            guard let self, let cell,
                  self.itemForCell[ObjectIdentifier(cell)] == itemKey else { return }
            (cell as? ViewModelBoundCell)?.viewModelStateDidChange?(state)
        }
    }

    func cellEndedDisplaying(_ cell: NSCollectionViewItem) {
        let cellKey = ObjectIdentifier(cell)
        guard let itemKey = itemForCell.removeValue(forKey: cellKey) else { return }
        if cellForItem[itemKey] == cellKey {
            cellForItem.removeValue(forKey: itemKey)
            cancels.removeValue(forKey: itemKey)?()
        }
    }

    deinit {
        for cancel in cancels.values { cancel() }
    }
}

// MARK: - Data source subclasses (visibility-driven state teardown)

@available(macOS 11.0, *)
@MainActor
final class RowsDeltaNSCollectionDataSource: DeltaNSCollectionDataSource<AnyObject> {
    let stateStore: DeltaRowStateStore

    init(
        collectionView: NSCollectionView,
        stateStore: DeltaRowStateStore,
        itemProvider: @escaping ItemProvider
    ) {
        self.stateStore = stateStore
        super.init(collectionView: collectionView, itemProvider: itemProvider)
    }

    override func collectionView(
        _ collectionView: NSCollectionView,
        didEndDisplaying item: NSCollectionViewItem,
        forRepresentedObjectAt indexPath: IndexPath
    ) {
        super.collectionView(collectionView, didEndDisplaying: item, forRepresentedObjectAt: indexPath)
        stateStore.cellEndedDisplaying(item)
    }
}

@available(macOS 11.0, *)
@MainActor
final class RowsSectionedDeltaNSCollectionDataSource: SectionedDeltaNSCollectionDataSource<AnyObject, AnyObject> {
    let stateStore: DeltaRowStateStore

    init(
        collectionView: NSCollectionView,
        stateStore: DeltaRowStateStore,
        itemProvider: @escaping ItemProvider,
        headerProvider: HeaderProvider?
    ) {
        self.stateStore = stateStore
        super.init(collectionView: collectionView, itemProvider: itemProvider, headerProvider: headerProvider)
    }

    override func collectionView(
        _ collectionView: NSCollectionView,
        didEndDisplaying item: NSCollectionViewItem,
        forRepresentedObjectAt indexPath: IndexPath
    ) {
        stateStore.cellEndedDisplaying(item)
    }
}

// MARK: - NSCollectionView binding entry points

/// Retains the bound data source for the collection view's lifetime (one binding per view;
/// rebinding replaces and tears down the previous one).
private let deltaRowsDataSourceKey = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)

@available(macOS 11.0, *)
extension NSCollectionView {
    /// Binds a flat `Flow<Delta<T>>` with typed row specs. Registers items, dispatches models to
    /// the first matching `Row`, auto-binds row state, and forwards selection. The returned data
    /// source is retained by the collection view; keep it only if you need its callbacks.
    @discardableResult
    public func items(
        _ stream: some AsyncSequence,
        @DeltaRowsBuilder _ content: () -> [any DeltaRowSpec]
    ) -> DeltaNSCollectionDataSource<AnyObject> {
        let specs = content()
        let rowSpecs = specs.filter { !$0.isHeader }
        let headerSpecs = specs.filter { $0.isHeader }
        for spec in specs { spec.register(in: self) }

        let store = DeltaRowStateStore()
        let dataSource = RowsDeltaNSCollectionDataSource(
            collectionView: self,
            stateStore: store,
            itemProvider: { collectionView, indexPath, model in
                guard let spec = rowSpecs.first(where: { $0.matches(model) }) else {
                    let empty = NSCollectionViewItem()
                    empty.view = NSView()
                    return empty
                }
                let item = collectionView.makeItem(withIdentifier: NSUserInterfaceItemIdentifier(spec.reuseId), for: indexPath)
                spec.configure(target: item, item: model)
                store.bind(cell: item, item: model)
                return item
            }
        )
        // Flat lists carry no header model, so the first Header spec serves the single section
        // header and its configure closure receives NSNull (use Header<AnyObject, _>).
        if let headerSpec = headerSpecs.first {
            dataSource.setSupplementaryViewProvider { collectionView, kind, indexPath in
                guard kind == NSCollectionView.elementKindSectionHeader else { return nil }
                let view = collectionView.makeSupplementaryView(
                    ofKind: kind,
                    withIdentifier: NSUserInterfaceItemIdentifier(headerSpec.reuseId),
                    for: indexPath
                )
                headerSpec.configure(target: view, item: NSNull())
                return view
            }
        }
        dataSource.onItemSelected = { [weak self] indexPath, model in
            guard let item = self?.item(at: indexPath) else { return }
            if let spec = rowSpecs.first(where: { $0.matches(model) }), spec.select(item: item, model: model) {
                return
            }
            (item as? ItemViewSelected)?.onSelected(model)
        }
        objc_setAssociatedObject(self, deltaRowsDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        dataSource.bind(erased: stream)
        return dataSource
    }

    /// Binds a `Flow<SectionedDelta<H, T>>` with typed row and header specs. Same behavior as
    /// `items(_:_:)` plus typed section-header dispatch.
    @discardableResult
    public func sections(
        _ stream: some AsyncSequence,
        @DeltaRowsBuilder _ content: () -> [any DeltaRowSpec]
    ) -> SectionedDeltaNSCollectionDataSource<AnyObject, AnyObject> {
        let specs = content()
        let rowSpecs = specs.filter { !$0.isHeader }
        let headerSpecs = specs.filter { $0.isHeader }
        for spec in specs { spec.register(in: self) }

        let store = DeltaRowStateStore()
        let dataSource = RowsSectionedDeltaNSCollectionDataSource(
            collectionView: self,
            stateStore: store,
            itemProvider: { collectionView, indexPath, model in
                guard let spec = rowSpecs.first(where: { $0.matches(model) }) else {
                    let empty = NSCollectionViewItem()
                    empty.view = NSView()
                    return empty
                }
                let item = collectionView.makeItem(withIdentifier: NSUserInterfaceItemIdentifier(spec.reuseId), for: indexPath)
                spec.configure(target: item, item: model)
                store.bind(cell: item, item: model)
                return item
            },
            headerProvider: headerSpecs.isEmpty ? nil : { collectionView, indexPath, header in
                guard let spec = headerSpecs.first(where: { $0.matches(header) }) else {
                    return NSView()
                }
                let view = collectionView.makeSupplementaryView(
                    ofKind: NSCollectionView.elementKindSectionHeader,
                    withIdentifier: NSUserInterfaceItemIdentifier(spec.reuseId),
                    for: indexPath
                )
                spec.configure(target: view, item: header)
                return view
            }
        )
        dataSource.onItemSelected = { [weak self] indexPath, model in
            guard let item = self?.item(at: indexPath) else { return }
            if let spec = rowSpecs.first(where: { $0.matches(model) }), spec.select(item: item, model: model) {
                return
            }
            (item as? ItemViewSelected)?.onSelected(model)
        }
        objc_setAssociatedObject(self, deltaRowsDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        dataSource.bind(to: stream)
        return dataSource
    }
}

// MARK: - Empty-state conveniences

@available(macOS 11.0, *)
extension DeltaNSCollectionDataSource {
    /// Shows `view` while the list is empty, hides it otherwise. Composes with any previously set
    /// `onItemsChanged`; setting `onItemsChanged` afterwards replaces this behavior.
    @available(*, deprecated, message: "Overlay empty states don't scroll with the list and aren't data-bound. Use the Kotlin `ifEmpty { }` operator to inject a placeholder item view model and register a Row spec for it; any layout invalidation this did moves into your section provider.")
    @discardableResult
    public func emptyView(_ view: NSView) -> Self {
        let previous = onItemsChanged
        onItemsChanged = { [weak self, weak view] items in
            previous?(items)
            let empty = (self?.totalSize ?? items.count) == 0
            view?.isHidden = !empty
        }
        return self
    }
}

@available(macOS 11.0, *)
extension SectionedDeltaNSCollectionDataSource {
    /// Shows `view` while the section whose header matches `headerType` is empty or absent.
    @available(*, deprecated, message: "Overlay empty states don't scroll with the list and aren't data-bound. Use the Kotlin `ifEmpty { }` operator to inject a placeholder item view model and register a Row spec for it; any layout invalidation this did moves into your section provider.")
    @discardableResult
    public func emptyView<MatchedHeader>(_ view: NSView, whenEmpty headerType: MatchedHeader.Type) -> Self {
        let previous = onSectionsChanged
        onSectionsChanged = { [weak view] sections in
            previous?(sections)
            let empty = sections.first { $0.header is MatchedHeader }?.items.isEmpty ?? true
            view?.isHidden = !empty
        }
        return self
    }

    /// Shows `view` while every section is empty.
    @available(*, deprecated, message: "Overlay empty states don't scroll with the list and aren't data-bound. Use the Kotlin `ifEmpty { }` operator to inject a placeholder item view model and register a Row spec for it; any layout invalidation this did moves into your section provider.")
    @discardableResult
    public func emptyView(_ view: NSView) -> Self {
        let previous = onSectionsChanged
        onSectionsChanged = { [weak view] sections in
            previous?(sections)
            let empty = sections.allSatisfy { $0.items.isEmpty }
            view?.isHidden = !empty
        }
        return self
    }
}
#endif

#if canImport(UIKit)
import UIKit

// MARK: - DeltaRows: typed registration + type-based cell differentiation
//
// Declarative successor to the flowlist-era `Layout<VM>` DSL, built on the delta data sources.
// A `Row` / `Header` spec pairs an item (view-model) type with a cell class in one declaration;
// binding a stream registers every cell, dispatches items to the first matching spec, drives
// per-row state via the app-installed `DeltaRowBinding.stateProvider`, and forwards selection.
//
// ```swift
// collectionView.sections(viewModel.sections) {
//     Header<IContactsSectionHeaderContacts, SectionHeaderView> { view, _ in ... }
//     Row<IScanActionItemViewModel, ScanActionItemCell>()
//     Row<IContactItemViewModel, ContactItemCell>()
// }
// .emptyView(emptyStateView, whenEmpty: IContactsSectionHeaderContacts.self)
// ```

// MARK: - Spec protocol

/// Type-erased registration spec produced by `Row` / `Header`. First matching spec wins, so
/// order specs most-specific-first; a `Row<AnyObject, _>` / `Header<AnyObject, _>` acts as a
/// trailing fallback.
@MainActor
public protocol DeltaRowSpec {
    var reuseId: String { get }
    var isHeader: Bool { get }
    func register(in collectionView: UICollectionView)
    func matches(_ item: AnyObject) -> Bool
    func configure(view: UIView, item: AnyObject)
    /// Returns true if this spec had an explicit selection handler and consumed the event.
    func select(cell: UICollectionViewCell, item: AnyObject) -> Bool
}

// MARK: - Row

/// Pairs an item type with a cell class. The reuse identifier defaults to the cell class name,
/// and the cell is registered automatically at bind time.
///
/// `VM` is intentionally unconstrained so Kotlin-interface protocols (e.g. `IContactItemViewModel`)
/// can be used as existentials; matching is a runtime `item is VM` check.
public struct Row<VM, Cell: UICollectionViewCell>: DeltaRowSpec {
    public let reuseId: String
    public var isHeader: Bool { false }

    private let configureClosure: (@MainActor (Cell, VM) -> Void)?
    private var selectClosure: (@MainActor (Cell, VM) -> Void)?

    public init(
        id: String = String(describing: Cell.self),
        configure: (@MainActor (Cell, VM) -> Void)? = nil
    ) {
        self.reuseId = id
        self.configureClosure = configure
    }

    /// Typed selection handler. Without one, selection auto-forwards to the cell's
    /// `ItemViewSelected.onSelected(_:)` if it conforms.
    public func onSelect(_ handler: @escaping @MainActor (Cell, VM) -> Void) -> Self {
        var copy = self
        copy.selectClosure = handler
        return copy
    }

    public func register(in collectionView: UICollectionView) {
        collectionView.register(Cell.self, forCellWithReuseIdentifier: reuseId)
    }

    public func matches(_ item: AnyObject) -> Bool {
        return item is VM
    }

    public func configure(view: UIView, item: AnyObject) {
        guard let cell = view as? Cell, let vm = item as? VM else { return }
        configureClosure?(cell, vm)
    }

    public func select(cell: UICollectionViewCell, item: AnyObject) -> Bool {
        guard let handler = selectClosure, let typedCell = cell as? Cell, let vm = item as? VM else {
            return false
        }
        handler(typedCell, vm)
        return true
    }
}

// MARK: - Header

/// Pairs a section-header type with a supplementary view class (section headers only).
public struct Header<H, View: UICollectionReusableView>: DeltaRowSpec {
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

    public func register(in collectionView: UICollectionView) {
        collectionView.register(
            View.self,
            forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader,
            withReuseIdentifier: reuseId
        )
    }

    public func matches(_ item: AnyObject) -> Bool {
        return item is H
    }

    public func configure(view: UIView, item: AnyObject) {
        guard let headerView = view as? View, let header = item as? H else { return }
        configureClosure?(headerView, header)
    }

    public func select(cell: UICollectionViewCell, item: AnyObject) -> Bool {
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

// MARK: - Cell binding protocols

/// Cells adopt this to receive their row item and its state emissions. State delivery requires an
/// installed `DeltaRowBinding.stateProvider`.
@objc public protocol ViewModelBoundCell: AnyObject {
    @objc optional func viewModelDidChange(_ viewModel: AnyObject)
    @objc optional func viewModelStateDidChange(_ state: Any)
}

/// Cells adopt this to receive selection when the matched `Row` has no explicit `.onSelect`.
public protocol ItemViewSelected {
    func onSelected(_ model: AnyObject)
}

// MARK: - State binding hook

/// deltalist cannot collect a consumer framework's ViewModel state flows itself (Flow interop is
/// per-framework), so the app installs this adapter once at startup. Given a row item it emits the
/// initial + subsequent states via `emit` and returns a cancel closure, or nil if the item has no
/// observable state.
///
/// ```swift
/// DeltaRowBinding.stateProvider = { item, emit in
///     guard let vm = item as? (any CoreViewModel) else { return nil }
///     if let initial = vm.initialState { emit(initial) }
///     let task = Task { @MainActor in for try await s in vm.state { emit(s) } }
///     return { task.cancel() }
/// }
/// ```
public enum DeltaRowBinding {
    @MainActor public static var stateProvider: ((AnyObject, @escaping (Any) -> Void) -> (() -> Void)?)?
}

// MARK: - Per-binding state observation

/// Owns the per-row state observations for one bound data source. Observation follows visibility:
/// bound at cell provide, cancelled when the cell scrolls off (didEndDisplaying), replaced when a
/// cell is reused for a different item, and torn down with the data source.
@available(iOS 14.0, *)
@MainActor
final class DeltaRowStateStore {
    private var cancels: [ObjectIdentifier: () -> Void] = [:]
    private var itemForCell: [ObjectIdentifier: ObjectIdentifier] = [:]
    private var cellForItem: [ObjectIdentifier: ObjectIdentifier] = [:]

    func bind(cell: UICollectionViewCell, item: AnyObject) {
        (cell as? ViewModelBoundCell)?.viewModelDidChange?(item)

        let cellKey = ObjectIdentifier(cell)
        let itemKey = ObjectIdentifier(item)

        // Cell reused for a different item: stop observing the item it used to show.
        if let previousItem = itemForCell[cellKey], previousItem != itemKey, cellForItem[previousItem] == cellKey {
            cancels.removeValue(forKey: previousItem)?()
            cellForItem.removeValue(forKey: previousItem)
        }
        itemForCell[cellKey] = itemKey
        cellForItem[itemKey] = cellKey

        guard let provider = DeltaRowBinding.stateProvider else { return }

        // Rebinding the same item (e.g. an Update mutation) replaces its observation.
        cancels.removeValue(forKey: itemKey)?()
        cancels[itemKey] = provider(item) { [weak self, weak cell] state in
            // Drop emissions once the cell shows a different item.
            guard let self, let cell,
                  self.itemForCell[ObjectIdentifier(cell)] == itemKey else { return }
            (cell as? ViewModelBoundCell)?.viewModelStateDidChange?(state)
        }
    }

    func cellEndedDisplaying(_ cell: UICollectionViewCell) {
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

@available(iOS 14.0, *)
@MainActor
final class RowsDeltaCollectionDataSource: DeltaCollectionDataSource<AnyObject> {
    let stateStore: DeltaRowStateStore

    init(
        collectionView: UICollectionView,
        stateStore: DeltaRowStateStore,
        cellProvider: @escaping CellProvider
    ) {
        self.stateStore = stateStore
        super.init(collectionView: collectionView, cellProvider: cellProvider)
    }

    override func collectionView(
        _ collectionView: UICollectionView,
        didEndDisplaying cell: UICollectionViewCell,
        forItemAt indexPath: IndexPath
    ) {
        stateStore.cellEndedDisplaying(cell)
    }
}

@available(iOS 14.0, *)
@MainActor
final class RowsSectionedDeltaCollectionDataSource: SectionedDeltaCollectionDataSource<AnyObject, AnyObject> {
    let stateStore: DeltaRowStateStore

    init(
        collectionView: UICollectionView,
        stateStore: DeltaRowStateStore,
        cellProvider: @escaping CellProvider,
        headerProvider: HeaderProvider?
    ) {
        self.stateStore = stateStore
        super.init(collectionView: collectionView, cellProvider: cellProvider, headerProvider: headerProvider)
    }

    public func collectionView(
        _ collectionView: UICollectionView,
        didEndDisplaying cell: UICollectionViewCell,
        forItemAt indexPath: IndexPath
    ) {
        stateStore.cellEndedDisplaying(cell)
    }
}

// MARK: - UICollectionView binding entry points

/// Retains the bound data source for the collection view's lifetime (one binding per view;
/// rebinding replaces and tears down the previous one).
private let deltaRowsDataSourceKey = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)

@available(iOS 14.0, *)
extension UICollectionView {
    /// Binds a flat `Flow<Delta<T>>` with typed row specs. Registers cells, dispatches items to
    /// the first matching `Row`, auto-binds row state, and forwards selection. The returned data
    /// source is retained by the collection view; keep it only if you need its callbacks
    /// (`onItemsChanged`, `onError`, ...).
    @discardableResult
    public func items(
        _ stream: some AsyncSequence,
        @DeltaRowsBuilder _ content: () -> [any DeltaRowSpec]
    ) -> DeltaCollectionDataSource<AnyObject> {
        let specs = content()
        let rowSpecs = specs.filter { !$0.isHeader }
        let headerSpecs = specs.filter { $0.isHeader }
        for spec in specs { spec.register(in: self) }

        let store = DeltaRowStateStore()
        let dataSource = RowsDeltaCollectionDataSource(
            collectionView: self,
            stateStore: store,
            cellProvider: { collectionView, indexPath, item in
                guard let spec = rowSpecs.first(where: { $0.matches(item) }) else {
                    return UICollectionViewCell()
                }
                let cell = collectionView.dequeueReusableCell(withReuseIdentifier: spec.reuseId, for: indexPath)
                spec.configure(view: cell, item: item)
                store.bind(cell: cell, item: item)
                return cell
            }
        )
        // Flat lists carry no header model, so the first Header spec serves the single section
        // header and its configure closure receives NSNull (use Header<AnyObject, _>).
        if let headerSpec = headerSpecs.first {
            dataSource.setSupplementaryViewProvider { collectionView, kind, indexPath in
                guard kind == UICollectionView.elementKindSectionHeader else { return nil }
                let view = collectionView.dequeueReusableSupplementaryView(
                    ofKind: kind,
                    withReuseIdentifier: headerSpec.reuseId,
                    for: indexPath
                )
                headerSpec.configure(view: view, item: NSNull())
                return view
            }
        }
        dataSource.onItemSelected = { [weak self] indexPath, item in
            guard let cell = self?.cellForItem(at: indexPath) else { return }
            if let spec = rowSpecs.first(where: { $0.matches(item) }), spec.select(cell: cell, item: item) {
                return
            }
            (cell as? ItemViewSelected)?.onSelected(item)
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
    ) -> SectionedDeltaCollectionDataSource<AnyObject, AnyObject> {
        let specs = content()
        let rowSpecs = specs.filter { !$0.isHeader }
        let headerSpecs = specs.filter { $0.isHeader }
        for spec in specs { spec.register(in: self) }

        let store = DeltaRowStateStore()
        let dataSource = RowsSectionedDeltaCollectionDataSource(
            collectionView: self,
            stateStore: store,
            cellProvider: { collectionView, indexPath, item in
                guard let spec = rowSpecs.first(where: { $0.matches(item) }) else {
                    return UICollectionViewCell()
                }
                let cell = collectionView.dequeueReusableCell(withReuseIdentifier: spec.reuseId, for: indexPath)
                spec.configure(view: cell, item: item)
                store.bind(cell: cell, item: item)
                return cell
            },
            headerProvider: headerSpecs.isEmpty ? nil : { collectionView, indexPath, header in
                guard let spec = headerSpecs.first(where: { $0.matches(header) }) else {
                    return UICollectionReusableView()
                }
                let view = collectionView.dequeueReusableSupplementaryView(
                    ofKind: UICollectionView.elementKindSectionHeader,
                    withReuseIdentifier: spec.reuseId,
                    for: indexPath
                )
                spec.configure(view: view, item: header)
                return view
            }
        )
        dataSource.onItemSelected = { [weak self] indexPath, item in
            guard let cell = self?.cellForItem(at: indexPath) else { return }
            if let spec = rowSpecs.first(where: { $0.matches(item) }), spec.select(cell: cell, item: item) {
                return
            }
            (cell as? ItemViewSelected)?.onSelected(item)
        }
        objc_setAssociatedObject(self, deltaRowsDataSourceKey, dataSource, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        dataSource.bind(to: stream)
        return dataSource
    }
}

// MARK: - Empty-state conveniences

@available(iOS 14.0, *)
extension DeltaCollectionDataSource {
    /// Shows `view` while the list is empty, hides it otherwise. Composes with any previously
    /// set `onItemsChanged`; setting `onItemsChanged` afterwards replaces this behavior.
    @discardableResult
    public func emptyView(_ view: UIView) -> Self {
        let previous = onItemsChanged
        onItemsChanged = { [weak self, weak view] items in
            previous?(items)
            let empty = (self?.totalSize ?? items.count) == 0
            view?.isHidden = !empty
        }
        return self
    }
}

@available(iOS 14.0, *)
extension SectionedDeltaCollectionDataSource {
    /// Shows `view` while the section whose header matches `headerType` is empty or absent.
    /// Also invalidates layout on section changes (header visibility may change). Composes with
    /// any previously set `onSectionsChanged`; setting it afterwards replaces this behavior.
    @discardableResult
    public func emptyView<MatchedHeader>(_ view: UIView, whenEmpty headerType: MatchedHeader.Type) -> Self {
        let previous = onSectionsChanged
        onSectionsChanged = { [weak self, weak view] sections in
            previous?(sections)
            let empty = sections.first { $0.header is MatchedHeader }?.items.isEmpty ?? true
            view?.isHidden = !empty
            self?.collectionView?.collectionViewLayout.invalidateLayout()
        }
        return self
    }

    /// Shows `view` while every section is empty.
    @discardableResult
    public func emptyView(_ view: UIView) -> Self {
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

import UIKit

/// Data source that extends DeltaDataSource to manage per-cell state collection.
/// Equivalent to Android's FlowDeltaAdapter.
///
/// Flow collection is tied to cell display lifecycle:
/// - Starts when a cell is about to be displayed
/// - Stops when a cell ends display or is recycled
@MainActor
public class FlowDeltaDataSource<T: Hashable, State>: NSObject, UICollectionViewDelegate {

    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T, State?) -> UICollectionViewCell?
    public typealias StateAccessor = (T) -> AsyncStream<State>
    public typealias StateHandler = (UICollectionViewCell, State) -> Void

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, UUID>!
    private var items: [T] = []
    private var shadowIds: [UUID] = []
    private var task: Task<Void, Never>?
    private var lazyList: (any LazyList)?

    // Per-cell state tracking
    private var cellTasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var cellStates: [ObjectIdentifier: State] = [:]

    private let cellProvider: CellProvider
    private let stateAccessor: StateAccessor
    private let stateHandler: StateHandler
    private let initialState: State?

    public var currentItems: [T] { items }

    // MARK: - Initialization

    /// Creates a new FlowDeltaDataSource.
    /// - Parameters:
    ///   - collectionView: The collection view to manage.
    ///   - stateAccessor: Function to extract an AsyncStream from an item.
    ///   - initialState: Optional initial state before first emission.
    ///   - cellProvider: Closure that provides cells for items.
    ///   - stateHandler: Closure called when state updates for a cell.
    public init(
        collectionView: UICollectionView,
        stateAccessor: @escaping StateAccessor,
        initialState: State? = nil,
        cellProvider: @escaping CellProvider,
        stateHandler: @escaping StateHandler
    ) {
        self.collectionView = collectionView
        self.stateAccessor = stateAccessor
        self.initialState = initialState
        self.cellProvider = cellProvider
        self.stateHandler = stateHandler
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: UICollectionView) {
        diffableDataSource = UICollectionViewDiffableDataSource<Int, UUID>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, identifier in
            guard let self = self else { return nil }
            guard let index = self.shadowIds.firstIndex(of: identifier),
                  index < self.items.count else { return nil }
            let item = self.items[index]
            return self.cellProvider(collectionView, indexPath, item, self.initialState)
        }
    }

    // MARK: - Binding

    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    applyDelta(delta)
                }
            } catch {
                // Stream completed
            }
        }
    }

    public func bind<S: AsyncSequence, L: LazyList>(to stream: S, lazyList: L) where S.Element == Delta<T>, L.Element == T {
        self.lazyList = lazyList
        bind(to: stream)
    }

    public func unbind() {
        task?.cancel()
        task = nil
        // Cancel all cell tasks
        for (_, task) in cellTasks {
            task.cancel()
        }
        cellTasks.removeAll()
        cellStates.removeAll()
        lazyList?.releaseAll()
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items

        switch delta.change {
        case .reload:
            shadowIds = delta.items.map { _ in UUID() }
            applySnapshot(animatingDifferences: false)

        case .mutations(let operations):
            for mutation in operations {
                switch mutation {
                case .insert(let index, let count):
                    let newIds = (0..<count).map { _ in UUID() }
                    shadowIds.insert(contentsOf: newIds, at: index)

                case .remove(let index, let count):
                    shadowIds.removeSubrange(index..<(index + count))

                case .update:
                    break

                case .move(let fromIndex, let toIndex, let count):
                    let movedIds = Array(shadowIds[fromIndex..<(fromIndex + count)])
                    shadowIds.removeSubrange(fromIndex..<(fromIndex + count))
                    let insertIndex = fromIndex < toIndex ? toIndex - count : toIndex
                    shadowIds.insert(contentsOf: movedIds, at: insertIndex)
                }
            }
            applySnapshot(animatingDifferences: true)
        }
    }

    private func applySnapshot(animatingDifferences: Bool) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, UUID>()
        snapshot.appendSections([0])
        snapshot.appendItems(shadowIds, toSection: 0)
        diffableDataSource.apply(snapshot, animatingDifferences: animatingDifferences)
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T {
        items[indexPath.item]
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard indexPath.item < items.count else { return }
        startStateCollection(for: cell, item: items[indexPath.item])
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        stopStateCollection(for: cell)
        lazyList?.release(index: indexPath.item)
    }

    // MARK: - State Collection

    private func startStateCollection(for cell: UICollectionViewCell, item: T) {
        let cellId = ObjectIdentifier(cell)
        cellTasks[cellId]?.cancel()

        let stream = stateAccessor(item)
        cellTasks[cellId] = Task { @MainActor [weak self, weak cell] in
            guard let self = self, let cell = cell else { return }

            for await state in stream {
                if Task.isCancelled { break }
                self.cellStates[cellId] = state
                self.stateHandler(cell, state)
            }
        }
    }

    private func stopStateCollection(for cell: UICollectionViewCell) {
        let cellId = ObjectIdentifier(cell)
        cellTasks[cellId]?.cancel()
        cellTasks.removeValue(forKey: cellId)
        cellStates.removeValue(forKey: cellId)
    }

    deinit {
        task?.cancel()
        for (_, task) in cellTasks {
            task.cancel()
        }
    }
}

// MARK: - Convenience Protocol

/// Protocol for cells that can receive state updates.
public protocol StatefulCell: UICollectionViewCell {
    associatedtype State
    func updateState(_ state: State)
}

/// Extension to create FlowDeltaDataSource with StatefulCell.
extension FlowDeltaDataSource where State: Any {
    /// Creates a FlowDeltaDataSource for cells conforming to StatefulCell.
    public static func withStatefulCells<Cell: StatefulCell>(
        collectionView: UICollectionView,
        stateAccessor: @escaping StateAccessor,
        initialState: State? = nil,
        cellProvider: @escaping (UICollectionView, IndexPath, T) -> Cell?
    ) -> FlowDeltaDataSource where Cell.State == State {
        FlowDeltaDataSource(
            collectionView: collectionView,
            stateAccessor: stateAccessor,
            initialState: initialState,
            cellProvider: { collectionView, indexPath, item, state in
                guard let cell = cellProvider(collectionView, indexPath, item) else { return nil }
                if let state = state {
                    cell.updateState(state)
                }
                return cell
            },
            stateHandler: { cell, state in
                (cell as? Cell)?.updateState(state)
            }
        )
    }
}

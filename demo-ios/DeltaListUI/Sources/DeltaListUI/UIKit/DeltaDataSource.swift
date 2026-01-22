import UIKit

/// Base data source for UICollectionView that automatically handles DeltaList updates.
/// Equivalent to Android's DeltaAdapter.
///
/// Uses UICollectionViewDiffableDataSource under the hood for efficient updates.
/// Manages lazy item lifecycle automatically.
@MainActor
public class DeltaDataSource<T: Hashable>: NSObject, UICollectionViewDelegate {

    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?
    public typealias SupplementaryViewProvider = (UICollectionView, String, IndexPath) -> UICollectionReusableView?

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, UUID>!
    private var items: [T] = []
    private var shadowIds: [UUID] = []
    private var task: Task<Void, Never>?
    private var lazyList: (any LazyList)?

    private let cellProvider: CellProvider

    /// The current items in the data source.
    public var currentItems: [T] { items }

    // MARK: - Initialization

    /// Creates a new DeltaDataSource.
    /// - Parameters:
    ///   - collectionView: The collection view to manage.
    ///   - cellProvider: Closure that provides cells for items.
    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
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
            return self.cellProvider(collectionView, indexPath, self.items[index])
        }
    }

    // MARK: - Binding

    /// Starts collecting deltas from the stream and applying them to the collection view.
    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    applyDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Starts collecting with lazy list support.
    public func bind<S: AsyncSequence, L: LazyList>(to stream: S, lazyList: L) where S.Element == Delta<T>, L.Element == T {
        self.lazyList = lazyList
        bind(to: stream)
    }

    /// Stops collecting deltas and releases all lazy items.
    public func unbind() {
        task?.cancel()
        task = nil
        lazyList?.releaseAll()
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items

        switch delta.change {
        case .reload:
            // Generate new shadow IDs for all items
            shadowIds = delta.items.map { _ in UUID() }
            applySnapshot(animatingDifferences: false)

        case .mutations(let operations):
            // Apply mutations to shadow IDs
            for mutation in operations {
                switch mutation {
                case .insert(let index, let count):
                    let newIds = (0..<count).map { _ in UUID() }
                    shadowIds.insert(contentsOf: newIds, at: index)

                case .remove(let index, let count):
                    shadowIds.removeSubrange(index..<(index + count))

                case .update:
                    // Updates don't change IDs
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

    /// Returns the item at the given index path.
    public func item(at indexPath: IndexPath) -> T {
        items[indexPath.item]
    }

    /// Returns the item at the given index path as a SoftValue.
    /// For regular lists, always returns .present.
    public func softItem(at indexPath: IndexPath) -> SoftValue<T>? {
        guard indexPath.item < items.count else { return nil }
        return .present(items[indexPath.item])
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Release lazy items when they leave the viewport
        lazyList?.release(index: indexPath.item)
    }

    // MARK: - Supplementary Views

    /// Sets the supplementary view provider.
    public func setSupplementaryViewProvider(_ provider: SupplementaryViewProvider?) {
        diffableDataSource.supplementaryViewProvider = provider
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Stable Item Extension

/// Extension for StableItem types that uses stable IDs directly.
@MainActor
public class StableDeltaDataSource<T: StableItem & Hashable>: NSObject, UICollectionViewDelegate where T.Value: Hashable {

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, Int>!
    private var items: [T] = []
    private var itemsByStableId: [Int: T] = [:]
    private var task: Task<Void, Never>?
    private var lazyList: (any LazyList)?

    private let cellProvider: CellProvider

    public var currentItems: [T] { items }

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: UICollectionView) {
        diffableDataSource = UICollectionViewDiffableDataSource<Int, Int>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, stableId in
            guard let self = self,
                  let item = self.itemsByStableId[stableId] else { return nil }
            return self.cellProvider(collectionView, indexPath, item)
        }
    }

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
        lazyList?.releaseAll()
    }

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { ($0.stableId, $0) })

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { $0.stableId }, toSection: 0)

        let animating = delta.change != .reload
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    public func item(at indexPath: IndexPath) -> T {
        items[indexPath.item]
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        lazyList?.release(index: indexPath.item)
    }

    deinit {
        task?.cancel()
    }
}

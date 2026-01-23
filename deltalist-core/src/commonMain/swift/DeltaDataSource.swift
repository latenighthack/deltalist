#if canImport(UIKit)
import UIKit
import Combine

/// Generic UICollectionView data source that observes a DeltaList (Kotlin Flow<Delta<T>>).
/// Equivalent to Android's DeltaAdapter.
///
/// Uses UICollectionViewDiffableDataSource under the hood for efficient animated updates.
///
/// Usage:
/// ```swift
/// let dataSource = DeltaCollectionDataSource<Item>(
///     collectionView: collectionView
/// ) { collectionView, indexPath, item in
///     let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "cell", for: indexPath)
///     cell.textLabel?.text = item.title
///     return cell
/// }
///
/// dataSource.bind(to: viewModel.items)
/// ```
@available(iOS 14.0, *)
@MainActor
public class DeltaCollectionDataSource<T: AnyObject>: NSObject, UICollectionViewDelegate {

    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?
    public typealias SupplementaryViewProvider = (UICollectionView, String, IndexPath) -> UICollectionReusableView?

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, UUID>!
    private var items: [T] = []
    private var shadowIds: [UUID] = []
    private var task: Task<Void, Never>?

    private let cellProvider: CellProvider

    /// The current items in the data source.
    public var currentItems: [T] { items }

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    // MARK: - Initialization

    /// Creates a new DeltaCollectionDataSource.
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

    /// Starts collecting deltas from a typed stream and applying them to the collection view.
    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    self.applyDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Starts collecting deltas from an erased stream (for type-erased Kotlin flows).
    public func bind(erased stream: some AsyncSequence) {
        unbind()
        task = Task { @MainActor in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    }
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Stops collecting deltas.
    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        // Convert Kotlin List to Swift Array
        items = delta.items as! [T]
        onItemsChanged?(items)

        // Handle change type using SKIE's onEnum pattern
        switch onEnum(of: delta.change) {
        case .reload:
            // Generate new shadow IDs for all items
            shadowIds = items.map { _ in UUID() }
            applySnapshot(animatingDifferences: false)

        case .mutations(let mutations):
            // Apply mutations to shadow IDs
            for operation in mutations.operations {
                switch onEnum(of: operation) {
                case .insert(let insert):
                    let count = Int(insert.count)
                    let index = Int(insert.index)
                    let newIds = (0..<count).map { _ in UUID() }
                    shadowIds.insert(contentsOf: newIds, at: index)

                case .remove(let remove):
                    let count = Int(remove.count)
                    let index = Int(remove.index)
                    shadowIds.removeSubrange(index..<(index + count))

                case .update:
                    // Updates don't change IDs, just reconfigure cells
                    break

                case .move(let move):
                    let count = Int(move.count)
                    let fromIndex = Int(move.fromIndex)
                    let toIndex = Int(move.toIndex)
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
    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.item < items.count else { return nil }
        return items[indexPath.item]
    }

    /// Returns the item at the given index.
    public func item(at index: Int) -> T? {
        guard index < items.count else { return nil }
        return items[index]
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override to handle lazy item release
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

/// Data source that uses stable IDs from StableItem types directly.
/// More efficient for lists using the withStableIds() operator.
@available(iOS 14.0, *)
@MainActor
public class StableDeltaCollectionDataSource<T: AnyObject>: NSObject, UICollectionViewDelegate {

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, Int32>!
    private var items: [T] = []
    private var itemsByStableId: [Int32: T] = [:]
    private var task: Task<Void, Never>?

    private let cellProvider: CellProvider
    private let stableIdExtractor: (T) -> Int32

    public var currentItems: [T] { items }

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Creates a data source for items with stable IDs.
    /// - Parameters:
    ///   - collectionView: The collection view to manage.
    ///   - stableIdExtractor: Closure that extracts the stable ID from an item.
    ///   - cellProvider: Closure that provides cells for items.
    public init(
        collectionView: UICollectionView,
        stableIdExtractor: @escaping (T) -> Int32,
        cellProvider: @escaping CellProvider
    ) {
        self.collectionView = collectionView
        self.stableIdExtractor = stableIdExtractor
        self.cellProvider = cellProvider
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: UICollectionView) {
        diffableDataSource = UICollectionViewDiffableDataSource<Int, Int32>(
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
                    self.applyDelta(delta)
                }
            } catch {
                // Stream completed
            }
        }
    }

    public func bind(erased stream: some AsyncSequence) {
        unbind()
        task = Task { @MainActor in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    }
                }
            } catch {
                // Stream completed
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items as! [T]
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating: Bool
        switch onEnum(of: delta.change) {
        case .reload:
            animating = false
        case .mutations:
            animating = true
        }

        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.item < items.count else { return nil }
        return items[indexPath.item]
    }

    public func item(at index: Int) -> T? {
        guard index < items.count else { return nil }
        return items[index]
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override to handle lazy item release
    }

    deinit {
        task?.cancel()
    }
}
#endif

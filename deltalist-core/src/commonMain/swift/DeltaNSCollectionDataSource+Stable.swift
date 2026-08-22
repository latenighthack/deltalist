#if canImport(AppKit) && !canImport(UIKit)
import AppKit
import Foundation

/// AppKit peer of `StableDeltaCollectionDataSource` (UIKit, DeltaDataSource.swift). Uses the stable
/// IDs from `StableItem` types directly, backed by `NSCollectionViewDiffableDataSource`
/// (available macOS 10.15+). More efficient for lists using the `withStableIds()` operator.
///
/// CRITICAL: Never access `delta.items` directly - always use `delta.loadedItems()` to avoid the
/// NSArray bridging catastrophe on soft lists.
@available(macOS 11.0, *)
@MainActor
public class StableDeltaNSCollectionDataSource<T: AnyObject>: NSObject, NSCollectionViewDelegate {

    public typealias ItemProvider = (NSCollectionView, IndexPath, T) -> NSCollectionViewItem?

    private weak var collectionView: NSCollectionView?
    private var diffableDataSource: NSCollectionViewDiffableDataSource<Int, Int32>!
    private var items: [T] = []
    private var itemsByStableId: [Int32: T] = [:]
    private var task: Task<Void, Never>?

    private let itemProvider: ItemProvider
    private let stableIdExtractor: (T) -> Int32

    public var currentItems: [T] { items }

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Invoked if the bound delta stream terminates with an error. Defaults to nil
    /// (silent); set it to route upstream failures to telemetry.
    public var onError: ((Error) -> Void)?

    public init(
        collectionView: NSCollectionView,
        stableIdExtractor: @escaping (T) -> Int32,
        itemProvider: @escaping ItemProvider
    ) {
        self.collectionView = collectionView
        self.stableIdExtractor = stableIdExtractor
        self.itemProvider = itemProvider
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: NSCollectionView) {
        diffableDataSource = NSCollectionViewDiffableDataSource<Int, Int32>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, stableId in
            guard let self = self,
                  let item = self.itemsByStableId[stableId] else { return nil }
            return self.itemProvider(collectionView, indexPath, item)
        }
    }

    // MARK: - Binding

    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    self.applyDelta(delta)
                }
            } catch {
                self.onError?(error)
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
                    } else if let delta = value as? Delta<AnyObject> {
                        self.applyDeltaErased(delta)
                    } else {
                        self.applyDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                self.onError?(error)
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        rebuildSnapshot(animating: !(delta.change is Change.Reload))
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        rebuildSnapshot(animating: !(delta.change is Change.Reload))
    }

    private func applyDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: access Delta properties directly if it's a Delta, else use
        // the shared runtime-selector path (DeltaSelector / DeltaIMPCache in DeltaList.swift).
        guard let deltaBase = delta as? Delta<NSObject> else {
            typealias Fn = @convention(c) (AnyObject, Selector) -> NSArray?
            if let imp = DeltaIMPCache.shared.imp(for: delta, DeltaSelector.loadedItems),
               let loadedArray = unsafeBitCast(imp, to: Fn.self)(delta, DeltaSelector.loadedItems) as? [AnyObject] {
                items = loadedArray.compactMap { $0 as? T }
                rebuildSnapshot(animating: false)
            }
            return
        }

        let loadedItems = deltaBase.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        rebuildSnapshot(animating: !(deltaBase.change is Change.Reload))
    }

    private func rebuildSnapshot(animating: Bool) {
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.item < items.count else { return nil }
        return items[indexPath.item]
    }

    public func item(at index: Int) -> T? {
        guard index < items.count else { return nil }
        return items[index]
    }

    deinit {
        task?.cancel()
    }
}

#endif

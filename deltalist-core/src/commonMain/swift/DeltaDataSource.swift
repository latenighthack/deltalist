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
                    } else if let delta = value as? Delta<AnyObject> {
                        // The generic parameter might not match exactly due to module boundaries
                        // Extract items and apply manually
                        let extractedItems = delta.items.compactMap { $0 as? T }
                        self.items = extractedItems
                        self.onItemsChanged?(extractedItems)

                        // Handle change type using SKIE's onEnum pattern
                        switch onEnum(of: delta.change) {
                        case .reload:
                            self.shadowIds = extractedItems.map { _ in UUID() }
                            self.applySnapshot(animatingDifferences: false)

                        case .mutations(let mutations):
                            for operation in mutations.operations {
                                switch onEnum(of: operation) {
                                case .insert(let insert):
                                    let count = Int(insert.count)
                                    let index = Int(insert.index)
                                    let newIds = (0..<count).map { _ in UUID() }
                                    self.shadowIds.insert(contentsOf: newIds, at: index)

                                case .remove(let remove):
                                    let count = Int(remove.count)
                                    let index = Int(remove.index)
                                    self.shadowIds.removeSubrange(index..<(index + count))

                                case .update:
                                    break

                                case .move(let move):
                                    let count = Int(move.count)
                                    let fromIndex = Int(move.fromIndex)
                                    let toIndex = Int(move.toIndex)
                                    let movedIds = Array(self.shadowIds[fromIndex..<(fromIndex + count)])
                                    self.shadowIds.removeSubrange(fromIndex..<(fromIndex + count))
                                    let insertIndex = fromIndex < toIndex ? toIndex - count : toIndex
                                    self.shadowIds.insert(contentsOf: movedIds, at: insertIndex)
                                }
                            }
                            self.applySnapshot(animatingDifferences: true)
                        }
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
                    // Try direct cast first, then try to extract items/change from Any
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        // The generic parameter might not match exactly due to module boundaries
                        // Create a wrapper that extracts the items as our expected type
                        let extractedItems = delta.items.compactMap { $0 as? T }
                        self.items = extractedItems
                        self.itemsByStableId = Dictionary(uniqueKeysWithValues: extractedItems.map { (self.stableIdExtractor($0), $0) })
                        self.onItemsChanged?(extractedItems)

                        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
                        snapshot.appendSections([0])
                        snapshot.appendItems(extractedItems.map { self.stableIdExtractor($0) }, toSection: 0)

                        let animating: Bool
                        switch onEnum(of: delta.change) {
                        case .reload:
                            animating = false
                        case .mutations:
                            animating = true
                        }

                        self.diffableDataSource.apply(snapshot, animatingDifferences: animating)
                    } else if let nsValue = value as? NSObject {
                        // Cross-module: SKIE re-exports types with different names (e.g., DemoCoreDelta)
                        // Use KVC to access properties
                        self.applyDeltaViaKVC(nsValue)
                    }
                }
            } catch {
                // Stream completed
            }
        }
    }

    /// Extracts Delta data using Key-Value Coding for cross-module compatibility.
    private func applyDeltaViaKVC(_ nsValue: NSObject) {
        // Get items array
        guard let itemsArray = nsValue.value(forKey: "items") as? [AnyObject] else {
            return
        }

        // Get change object
        guard let changeObj = nsValue.value(forKey: "change") else {
            return
        }

        // Extract items
        let extractedItems = itemsArray.compactMap { $0 as? T }
        self.items = extractedItems
        self.itemsByStableId = Dictionary(uniqueKeysWithValues: extractedItems.map { (self.stableIdExtractor($0), $0) })
        self.onItemsChanged?(extractedItems)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(extractedItems.map { self.stableIdExtractor($0) }, toSection: 0)

        // Determine if we should animate based on change type
        let typeName = String(describing: type(of: changeObj))
        let animating = !typeName.contains("Reload")

        self.diffableDataSource.apply(snapshot, animatingDifferences: animating)
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

// MARK: - Sectioned Delta Collection Data Source

/// Traditional UICollectionViewDataSource that observes a SectionedDeltaList.
/// NO DiffableDataSource - direct data source/delegate implementation with performBatchUpdates.
///
/// Usage:
/// ```swift
/// let dataSource = SectionedDeltaCollectionDataSource<SectionHeader, Item>(
///     collectionView: collectionView,
///     cellProvider: { collectionView, indexPath, item in
///         // return configured cell
///     },
///     headerProvider: { collectionView, indexPath, header in
///         // return configured header view
///     }
/// )
/// dataSource.bind(to: viewModel.sections)
/// ```
@available(iOS 14.0, *)
@MainActor
public class SectionedDeltaCollectionDataSource<H: AnyObject, T: AnyObject>: NSObject,
    UICollectionViewDataSource,
    UICollectionViewDelegate
{
    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell
    public typealias HeaderProvider = (UICollectionView, IndexPath, H) -> UICollectionReusableView

    // MARK: - Section Data

    public struct SectionData {
        public let header: H
        public var items: [T]

        public init(header: H, items: [T]) {
            self.header = header
            self.items = items
        }
    }

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    private let cellProvider: CellProvider
    private let headerProvider: HeaderProvider?

    /// Callback when sections are updated.
    public var onSectionsChanged: (([SectionData]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback when a header is tapped (if headers are interactive).
    public var onHeaderSelected: ((Int, H) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider,
        headerProvider: HeaderProvider? = nil
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        self.headerProvider = headerProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    /// Binds to a SectionedDeltaList flow.
    public func bind(to flow: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    // Try direct cast first (same module)
                    if let sectionedDelta = value as? SectionedDelta<H, T> {
                        self.applySectionedDelta(sectionedDelta)
                    } else if let sectionedDelta = value as? SectionedDelta<AnyObject, AnyObject> {
                        self.applySectionedDeltaErased(sectionedDelta)
                    } else if let nsValue = value as? NSObject {
                        // Cross-module: SKIE re-exports types with different names (e.g., DemoCoreSectionedDelta)
                        // Use KVC to access properties
                        self.applySectionedDeltaViaKVC(nsValue)
                    }
                }
            } catch {}
        }
    }

    /// Extracts SectionedDelta data using Key-Value Coding for cross-module compatibility.
    private func applySectionedDeltaViaKVC(_ nsValue: NSObject) {
        // Get sections array
        guard let sectionsArray = nsValue.value(forKey: "sections") as? [AnyObject] else {
            print("[SectionedDataSource] Could not extract sections via KVC")
            return
        }

        // Get change object
        guard let changeObj = nsValue.value(forKey: "change") else {
            print("[SectionedDataSource] Could not extract change via KVC")
            return
        }

        // Convert sections
        let newSections = sectionsArray.compactMap { sectionObj -> SectionData? in
            guard let section = sectionObj as? NSObject,
                  let header = section.value(forKey: "header") as? H else {
                return nil
            }
            let items = (section.value(forKey: "items") as? [AnyObject])?.compactMap { $0 as? T } ?? []
            return SectionData(header: header, items: items)
        }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
            return
        }

        // Determine change type and apply
        let typeName = String(describing: type(of: changeObj))

        if typeName.contains("Reload") {
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
        } else if typeName.contains("Sections"), let changeNS = changeObj as? NSObject {
            // Section-level mutations
            sections = newSections
            onSectionsChanged?(newSections)

            if let mutations = changeNS.value(forKey: "mutations") as? [AnyObject] {
                collectionView?.performBatchUpdates {
                    for mutation in mutations {
                        guard let mutationNS = mutation as? NSObject else { continue }
                        let mutationType = String(describing: type(of: mutation))

                        if mutationType.contains("Insert") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            collectionView?.insertSections(IndexSet(index..<(index + count)))
                        } else if mutationType.contains("Remove") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            collectionView?.deleteSections(IndexSet(index..<(index + count)))
                        } else if mutationType.contains("Update") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            collectionView?.reloadSections(IndexSet(integer: index))
                        } else if mutationType.contains("Move") {
                            let fromIndex = (mutationNS.value(forKey: "fromIndex") as? Int) ?? 0
                            let toIndex = (mutationNS.value(forKey: "toIndex") as? Int) ?? 0
                            collectionView?.moveSection(fromIndex, toSection: toIndex)
                        }
                    }
                }
            } else {
                collectionView?.reloadData()
            }
        } else if typeName.contains("Items"), let changeNS = changeObj as? NSObject {
            // Item-level mutations
            sections = newSections
            onSectionsChanged?(newSections)

            let sectionIndex = (changeNS.value(forKey: "section") as? Int) ?? 0
            if let mutations = changeNS.value(forKey: "mutations") as? [AnyObject] {
                collectionView?.performBatchUpdates {
                    for mutation in mutations {
                        guard let mutationNS = mutation as? NSObject else { continue }
                        let mutationType = String(describing: type(of: mutation))

                        if mutationType.contains("Insert") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.insertItems(at: indexPaths)
                        } else if mutationType.contains("Remove") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.deleteItems(at: indexPaths)
                        } else if mutationType.contains("Update") {
                            let index = (mutationNS.value(forKey: "index") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            let indexPaths = (0..<count).map { IndexPath(item: index + $0, section: sectionIndex) }
                            collectionView?.reloadItems(at: indexPaths)
                        } else if mutationType.contains("Move") {
                            let fromIndex = (mutationNS.value(forKey: "fromIndex") as? Int) ?? 0
                            let toIndex = (mutationNS.value(forKey: "toIndex") as? Int) ?? 0
                            let count = (mutationNS.value(forKey: "count") as? Int) ?? 1
                            for i in 0..<count {
                                collectionView?.moveItem(
                                    at: IndexPath(item: fromIndex + i, section: sectionIndex),
                                    to: IndexPath(item: toIndex + i, section: sectionIndex)
                                )
                            }
                        }
                    }
                }
            } else {
                collectionView?.reloadData()
            }
        } else {
            // Unknown change type, just reload
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application

    private func applySectionedDelta(_ delta: SectionedDelta<H, T>) {
        let newSections = delta.sections.compactMap { section -> SectionData? in
            guard let header = section.header else { return nil }
            let items = section.items.compactMap { $0 as? T }
            return SectionData(header: header, items: items)
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaErased(_ delta: SectionedDelta<AnyObject, AnyObject>) {
        let newSections = delta.sections.compactMap { section -> SectionData? in
            guard let header = section.header as? H else { return nil }
            let items = section.items.compactMap { $0 as? T }
            return SectionData(header: header, items: items)
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applyChanges(newSections: [SectionData], change: SectionedChange) {
        sections = newSections
        onSectionsChanged?(newSections)

        guard let collectionView = collectionView else { return }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView.reloadData()
            return
        }

        switch onEnum(of: change) {
        case .reload:
            collectionView.reloadData()

        case .sections(let sectionChanges):
            // Section-level mutations (add/remove/move/update sections)
            collectionView.performBatchUpdates {
                for mutation in sectionChanges.mutations {
                    switch onEnum(of: mutation) {
                    case .insert(let insert):
                        let sectionIndices = IndexSet(Int(insert.index)..<Int(insert.index + insert.count))
                        collectionView.insertSections(sectionIndices)

                    case .remove(let remove):
                        let sectionIndices = IndexSet(Int(remove.index)..<Int(remove.index + remove.count))
                        collectionView.deleteSections(sectionIndices)

                    case .update(let update):
                        collectionView.reloadSections(IndexSet(integer: Int(update.index)))

                    case .move(let move):
                        collectionView.moveSection(Int(move.fromIndex), toSection: Int(move.toIndex))
                    }
                }
            }

        case .items(let itemChanges):
            // Item-level mutations within a specific section
            let sectionIndex = Int(itemChanges.section)
            collectionView.performBatchUpdates {
                for mutation in itemChanges.mutations {
                    switch onEnum(of: mutation) {
                    case .insert(let insert):
                        let indexPaths = (0..<Int(insert.count)).map {
                            IndexPath(item: Int(insert.index) + $0, section: sectionIndex)
                        }
                        collectionView.insertItems(at: indexPaths)

                    case .remove(let remove):
                        let indexPaths = (0..<Int(remove.count)).map {
                            IndexPath(item: Int(remove.index) + $0, section: sectionIndex)
                        }
                        collectionView.deleteItems(at: indexPaths)

                    case .update(let update):
                        let indexPaths = (0..<Int(update.count)).map {
                            IndexPath(item: Int(update.index) + $0, section: sectionIndex)
                        }
                        collectionView.reloadItems(at: indexPaths)

                    case .move(let move):
                        for i in 0..<Int(move.count) {
                            let from = IndexPath(item: Int(move.fromIndex) + i, section: sectionIndex)
                            let to = IndexPath(item: Int(move.toIndex) + i, section: sectionIndex)
                            collectionView.moveItem(at: from, to: to)
                        }
                    }
                }
            }
        }
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        return sections.count
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            fatalError("Index out of bounds: section \(indexPath.section), item \(indexPath.item)")
        }
        let item = sections[indexPath.section].items[indexPath.item]
        return cellProvider(collectionView, indexPath, item)
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        guard kind == UICollectionView.elementKindSectionHeader,
              let headerProvider = headerProvider,
              indexPath.section < sections.count else {
            return UICollectionReusableView()
        }
        let header = sections[indexPath.section].header
        return headerProvider(collectionView, indexPath, header)
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return }
        let item = sections[indexPath.section].items[indexPath.item]
        onItemSelected?(indexPath, item)
        collectionView.deselectItem(at: indexPath, animated: true)
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return nil }
        return sections[indexPath.section].items[indexPath.item]
    }

    public func header(at section: Int) -> H? {
        guard section < sections.count else { return nil }
        return sections[section].header
    }

    deinit {
        task?.cancel()
    }
}
#endif

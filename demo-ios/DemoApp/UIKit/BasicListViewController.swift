import UIKit
import Combine
import DemoCore
import DeltaListCore

/// UIKit implementation of the basic list demo using UICollectionView.
/// Uses StableDeltaCollectionDataSource from DeltaListCore for simplified data binding.
@available(iOS 14.0, *)
@MainActor
class BasicListViewController: UIViewController {
    private let viewModel: ListViewModel
    private var collectionView: UICollectionView!

    // StableDeltaCollectionDataSource from DeltaListCore - handles diffing automatically!
    // Use DemoCore.StableItem to disambiguate from DeltaListCore types
    private var dataSource: DeltaListCore.StableDeltaCollectionDataSource<DemoCore.StableItem>!

    // Track cell state observers for tick counts
    private var cellObservers: [Int32: DeltaListCore.ItemStateObserver<DemoCore.KotlinInt>] = [:]

    init(viewModel: ListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCollectionView()
        setupDataSource()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Perform main-actor cleanup here (deinit is nonisolated)
        dataSource?.unbind()
        for observer in cellObservers.values {
            observer.pause()
        }
        cellObservers.removeAll()
    }

    private func setupCollectionView() {
        let layout = createLayout()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        view.addSubview(collectionView)
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<TickingItemCell, DemoCore.StableItem> { [weak self] (cell: TickingItemCell, indexPath: IndexPath, stableItem: DemoCore.StableItem) in
            guard let tickingItem = stableItem.value as? TickingItem else { return }
            cell.configure(stableId: stableItem.stableId, tickingItem: tickingItem)

            // Start observing tick count using ItemStateObserver from DeltaListCore
            self?.startStateObservation(for: stableItem.stableId, tickingItem: tickingItem, cell: cell)
        }

        // Use StableDeltaCollectionDataSource from DeltaListCore
        dataSource = DeltaListCore.StableDeltaCollectionDataSource<DemoCore.StableItem>(
            collectionView: collectionView,
            stableIdExtractor: { (item: DemoCore.StableItem) -> Int32 in item.stableId }
        ) { (collectionView: UICollectionView, indexPath: IndexPath, item: DemoCore.StableItem) -> UICollectionViewCell? in
            collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: item)
        }

        // Bind to the flow - one line! No manual snapshot management!
        dataSource.bind(erased: viewModel.tickingItems)
    }

    func updateItems(_ items: [DemoCore.StableItem]) {
        // External updates are handled automatically by the data source binding
        // This method is kept for compatibility with the SwiftUI wrapper
    }

    // MARK: - State Observation using DeltaListCore

    private func startStateObservation(for stableId: Int32, tickingItem: TickingItem, cell: TickingItemCell) {
        // Cancel existing observer for this item
        cellObservers[stableId]?.pause()

        // Create a new ItemStateObserver from DeltaListCore
        // The updated API accepts any AsyncSequence directly, no wrapper needed
        let observer = DeltaListCore.ItemStateObserver<DemoCore.KotlinInt>(
            initial: DemoCore.KotlinInt(int: 0),
            flow: { tickingItem.tickCount }
        )

        cellObservers[stableId] = observer

        // Observe changes using Combine
        observer.$value
            .receive(on: DispatchQueue.main)
            .sink { [weak cell] (newValue: DemoCore.KotlinInt) in
                cell?.updateTickCount(Int(newValue.intValue))
            }
            .store(in: &cell.cancellables)
    }

    private func stopStateObservation(for stableId: Int32) {
        cellObservers[stableId]?.pause()
        cellObservers.removeValue(forKey: stableId)
    }

    deinit {
        // deinit is nonisolated; schedule main-actor cleanup if we somehow missed viewWillDisappear
        Task { @MainActor [dataSource, cellObservers] in
            dataSource?.unbind()
            for observer in cellObservers.values {
                observer.pause()
            }
        }
    }
}

// MARK: - Ticking Item Cell

@available(iOS 14.0, *)
private class TickingItemCell: UICollectionViewListCell {
    private var titleLabel: UILabel!
    private var tickLabel: UILabel!
    private var stableId: Int32 = 0
    var cancellables = Set<AnyCancellable>()

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        cancellables.removeAll()
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .vertical
        stackView.spacing = 4
        stackView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        tickLabel = UILabel()
        tickLabel.font = .preferredFont(forTextStyle: .caption1)
        tickLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(titleLabel)
        stackView.addArrangedSubview(tickLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(stableId: Int32, tickingItem: TickingItem) {
        self.stableId = stableId
        titleLabel.text = tickingItem.item.title
        tickLabel.text = "Ticks: 0 | StableId: \(stableId)"
    }

    func updateTickCount(_ count: Int) {
        tickLabel.text = "Ticks: \(count) | StableId: \(stableId)"
    }
}

import UIKit
import Combine

/// UIKit implementation of the basic list demo using UICollectionView.
@MainActor
class BasicListViewController: UIViewController {
    private let viewModel: ListViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var cancellables = Set<AnyCancellable>()

    // Track cell state observers
    private var cellTasks: [String: Task<Void, Never>] = [:]

    init(viewModel: ListViewModelAdapter) {
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
        bindViewModel()
    }

    private func setupCollectionView() {
        let layout = createLayout()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        collectionView.delegate = self
        view.addSubview(collectionView)
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<TickingItemCell, StableTickingItemWrapper> { [weak self] cell, indexPath, tickingItem in
            cell.configure(with: tickingItem)

            // Start observing tick count
            self?.startStateObservation(for: tickingItem, cell: cell)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { [weak self] collectionView, indexPath, itemId in
            guard let self = self,
                  let tickingItem = self.viewModel.tickingItems.first(where: { $0.item.id == itemId }) else {
                return nil
            }
            return collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: tickingItem)
        }
    }

    private func bindViewModel() {
        viewModel.$tickingItems
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.updateSnapshot(items: items)
            }
            .store(in: &cancellables)
    }

    private func updateSnapshot(items: [StableTickingItemWrapper]) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { $0.item.id }, toSection: 0)
        dataSource.apply(snapshot, animatingDifferences: true)
    }

    func updateItems(_ items: [StableTickingItemWrapper]) {
        updateSnapshot(items: items)
    }

    // MARK: - State Observation

    private func startStateObservation(for tickingItem: StableTickingItemWrapper, cell: TickingItemCell) {
        let itemId = tickingItem.item.id

        // Cancel existing task for this item
        cellTasks[itemId]?.cancel()

        // Start new observation task using Combine
        cellTasks[itemId] = Task { @MainActor in
            for await tickCount in tickingItem.$tickCount.values {
                if Task.isCancelled { break }
                cell.updateTickCount(tickCount)
            }
        }
    }

    private func stopStateObservation(for itemId: String) {
        cellTasks[itemId]?.cancel()
        cellTasks.removeValue(forKey: itemId)
    }

    deinit {
        for task in cellTasks.values {
            task.cancel()
        }
    }
}

// MARK: - UICollectionViewDelegate

extension BasicListViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Release lazy item and stop state observation
        if let tickingItem = viewModel.tickingItems[safe: indexPath.item] {
            stopStateObservation(for: tickingItem.item.id)
            tickingItem.stop()
        }
    }
}

// MARK: - Ticking Item Cell

private class TickingItemCell: UICollectionViewListCell {
    private var titleLabel: UILabel!
    private var tickLabel: UILabel!
    private var stableId: Int = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
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

    func configure(with tickingItem: StableTickingItemWrapper) {
        self.stableId = tickingItem.stableId
        titleLabel.text = tickingItem.item.title
        tickLabel.text = "Ticks: \(tickingItem.tickCount) | StableId: \(stableId)"
    }

    func updateTickCount(_ count: Int) {
        tickLabel.text = "Ticks: \(count) | StableId: \(stableId)"
    }
}

// MARK: - Array Extension

private extension Array {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

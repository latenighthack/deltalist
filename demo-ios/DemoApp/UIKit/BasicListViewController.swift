import UIKit
import Combine
import DemoCore

/// UIKit implementation of the basic list demo using UICollectionView.
@MainActor
class BasicListViewController: UIViewController {
    private let viewModel: ListViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, Int32>!
    private var cancellables = Set<AnyCancellable>()

    // Track cell state observers
    private var cellTasks: [Int32: Task<Void, Never>] = [:]

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
        let cellRegistration = UICollectionView.CellRegistration<TickingItemCell, TickingItemWrapper> { [weak self] cell, indexPath, tickingItem in
            cell.configure(with: tickingItem)

            // Start observing tick count
            self?.startStateObservation(for: tickingItem, cell: cell)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, Int32>(collectionView: collectionView) { [weak self] collectionView, indexPath, stableId in
            guard let self = self,
                  let tickingItem = self.viewModel.tickingItems.first(where: { $0.stableId == stableId }) else {
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

    private func updateSnapshot(items: [TickingItemWrapper]) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { $0.stableId }, toSection: 0)
        dataSource.apply(snapshot, animatingDifferences: true)
    }

    func updateItems(_ items: [TickingItemWrapper]) {
        updateSnapshot(items: items)
    }

    // MARK: - State Observation

    private func startStateObservation(for tickingItem: TickingItemWrapper, cell: TickingItemCell) {
        let stableId = tickingItem.stableId

        // Cancel existing task for this item
        cellTasks[stableId]?.cancel()

        // Start new observation task using Combine
        cellTasks[stableId] = Task { @MainActor in
            for await tickCount in tickingItem.$tickCount.values {
                if Task.isCancelled { break }
                cell.updateTickCount(Int(tickCount))
            }
        }
    }

    private func stopStateObservation(for stableId: Int32) {
        cellTasks[stableId]?.cancel()
        cellTasks.removeValue(forKey: stableId)
    }

    deinit {
        for task in cellTasks.values {
            task.cancel()
        }
    }
}

// MARK: - UICollectionViewDelegate

extension BasicListViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Resume observation when item scrolls into view
        if let tickingItem = viewModel.tickingItems[safe: indexPath.item] {
            tickingItem.resumeObservation()
        }
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Pause observation when item scrolls out of view
        if let tickingItem = viewModel.tickingItems[safe: indexPath.item] {
            stopStateObservation(for: tickingItem.stableId)
            tickingItem.pauseObservation()
        }
    }
}

// MARK: - Ticking Item Cell

private class TickingItemCell: UICollectionViewListCell {
    private var titleLabel: UILabel!
    private var tickLabel: UILabel!
    private var stableId: Int32 = 0

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

    func configure(with tickingItem: TickingItemWrapper) {
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

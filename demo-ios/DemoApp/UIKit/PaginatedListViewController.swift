import UIKit
import Combine

/// UIKit implementation of the paginated list demo.
@MainActor
class PaginatedListViewController: UIViewController {
    private let viewModel: PaginatedListViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, PaginatedItem>!
    private var cancellables = Set<AnyCancellable>()

    init(viewModel: PaginatedListViewModelAdapter) {
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
        let numberCellRegistration = UICollectionView.CellRegistration<NumberCell, Int> { cell, indexPath, number in
            cell.configure(number: number, index: indexPath.item)
        }

        let loadingCellRegistration = UICollectionView.CellRegistration<LoadingCell, Int> { cell, indexPath, _ in
            cell.configure(index: indexPath.item)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, PaginatedItem>(collectionView: collectionView) { collectionView, indexPath, item in
            switch item {
            case .loaded(let number):
                return collectionView.dequeueConfiguredReusableCell(using: numberCellRegistration, for: indexPath, item: number)
            case .loading(let index):
                return collectionView.dequeueConfiguredReusableCell(using: loadingCellRegistration, for: indexPath, item: index)
            }
        }
    }

    private func bindViewModel() {
        viewModel.$numbers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] numbers in
                self?.updateSnapshot(numbers: numbers)
            }
            .store(in: &cancellables)
    }

    private func updateSnapshot(numbers: [Int]) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, PaginatedItem>()
        snapshot.appendSections([0])

        let items = numbers.map { PaginatedItem.loaded($0) }
        snapshot.appendItems(items, toSection: 0)

        // Add loading indicator if more items available
        if viewModel.loadedCount < 10_000 {
            snapshot.appendItems([.loading(numbers.count)], toSection: 0)
        }

        dataSource.apply(snapshot, animatingDifferences: true)
    }
}

// MARK: - UICollectionViewDelegate

extension PaginatedListViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Pagination is handled by the Kotlin ViewModel automatically
    }
}

// MARK: - Paginated Item

private enum PaginatedItem: Hashable {
    case loaded(Int)
    case loading(Int)
}

// MARK: - Number Cell

private class NumberCell: UICollectionViewListCell {
    private var numberLabel: UILabel!
    private var indexLabel: UILabel!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .horizontal
        stackView.translatesAutoresizingMaskIntoConstraints = false

        numberLabel = UILabel()
        numberLabel.font = .preferredFont(forTextStyle: .title2)

        indexLabel = UILabel()
        indexLabel.font = .preferredFont(forTextStyle: .caption1)
        indexLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(numberLabel)
        stackView.addArrangedSubview(UIView()) // Spacer
        stackView.addArrangedSubview(indexLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(number: Int, index: Int) {
        numberLabel.text = "#\(number)"
        indexLabel.text = "index: \(index)"
    }
}

// MARK: - Loading Cell

private class LoadingCell: UICollectionViewListCell {
    private var activityIndicator: UIActivityIndicatorView!
    private var label: UILabel!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .horizontal
        stackView.spacing = 12
        stackView.translatesAutoresizingMaskIntoConstraints = false

        activityIndicator = UIActivityIndicatorView(style: .medium)
        activityIndicator.startAnimating()

        label = UILabel()
        label.text = "Loading..."
        label.font = .preferredFont(forTextStyle: .body)
        label.textColor = .secondaryLabel

        stackView.addArrangedSubview(activityIndicator)
        stackView.addArrangedSubview(label)
        stackView.addArrangedSubview(UIView()) // Spacer

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(index: Int) {
        // Configure if needed
    }
}

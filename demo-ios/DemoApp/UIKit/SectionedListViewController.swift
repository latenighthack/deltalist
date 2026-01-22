import UIKit
import Combine

/// UIKit implementation of the sectioned list demo.
@MainActor
class SectionedListViewController: UIViewController {
    private let viewModel: SectionedListViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, SectionRowWrapper>!
    private var cancellables = Set<AnyCancellable>()

    var selectedSectionIndex: Int = -1 {
        didSet {
            if oldValue != selectedSectionIndex {
                collectionView.reloadData()
            }
        }
    }

    var onSectionSelected: ((Int) -> Void)?

    init(viewModel: SectionedListViewModelAdapter) {
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
        config.showsSeparators = false
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        let headerRegistration = UICollectionView.CellRegistration<SectionHeaderCell, SectionHeaderWrapper> { [weak self] cell, indexPath, header in
            let sectionIndex = self?.sectionIndex(for: indexPath) ?? 0
            cell.configure(with: header, isSelected: sectionIndex == self?.selectedSectionIndex)
        }

        let itemRegistration = UICollectionView.CellRegistration<SectionItemCell, ItemWrapper> { cell, indexPath, item in
            cell.configure(with: item)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, SectionRowWrapper>(collectionView: collectionView) { collectionView, indexPath, row in
            switch row {
            case .header(let header):
                return collectionView.dequeueConfiguredReusableCell(using: headerRegistration, for: indexPath, item: header)
            case .itemRow(let item, _):
                return collectionView.dequeueConfiguredReusableCell(using: itemRegistration, for: indexPath, item: item)
            }
        }
    }

    private func bindViewModel() {
        viewModel.$flattenedRows
            .receive(on: DispatchQueue.main)
            .sink { [weak self] rows in
                self?.updateSnapshot(rows: rows)
            }
            .store(in: &cancellables)
    }

    private func updateSnapshot(rows: [SectionRowWrapper]) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, SectionRowWrapper>()
        snapshot.appendSections([0])
        snapshot.appendItems(rows, toSection: 0)
        dataSource.apply(snapshot, animatingDifferences: true)
    }

    private func sectionIndex(for indexPath: IndexPath) -> Int {
        let rows = viewModel.flattenedRows
        var count = 0
        for i in 0..<min(indexPath.item, rows.count) {
            if case .header = rows[i] {
                count += 1
            }
        }
        return count - 1
    }
}

// MARK: - UICollectionViewDelegate

extension SectionedListViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: true)

        guard indexPath.item < viewModel.flattenedRows.count else { return }
        let row = viewModel.flattenedRows[indexPath.item]

        if case .header = row {
            let sectionIndex = self.sectionIndex(for: indexPath)
            onSectionSelected?(sectionIndex)
        }
    }
}

// MARK: - Section Header Cell

private class SectionHeaderCell: UICollectionViewListCell {
    private var titleLabel: UILabel!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textColor = .white
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(titleLabel)
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            titleLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
            titleLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12)
        ])
    }

    func configure(with header: SectionHeaderWrapper, isSelected: Bool) {
        titleLabel.text = header.title
        contentView.backgroundColor = UIColor(header.color).withAlphaComponent(isSelected ? 1.0 : 0.8)
    }
}

// MARK: - Section Item Cell

private class SectionItemCell: UICollectionViewListCell {
    private var titleLabel: UILabel!
    private var idLabel: UILabel!

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
        stackView.spacing = 2
        stackView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        idLabel = UILabel()
        idLabel.font = .preferredFont(forTextStyle: .caption1)
        idLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(titleLabel)
        stackView.addArrangedSubview(idLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            stackView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            stackView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            stackView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -8)
        ])
    }

    func configure(with item: ItemWrapper) {
        titleLabel.text = item.title
        idLabel.text = "ID: \(item.id.prefix(8))..."
    }
}

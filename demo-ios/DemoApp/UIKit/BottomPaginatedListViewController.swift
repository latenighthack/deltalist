import UIKit
import DemoCore

/// UIKit implementation of the bottom-anchored ("chat-style") paginated list demo.
/// Uses DeltaCollectionDataSource with soft list support; starts scrolled to the bottom and
/// renders not-yet-loaded slots as skeleton cells.
@MainActor
class BottomPaginatedListViewController: UIViewController {
    private let viewModel: BottomPaginatedListViewModel
    private var collectionView: UICollectionView!
    private var dataSource: DeltaCollectionDataSource<KotlinInt>!
    private var didInitialScroll = false

    init(viewModel: BottomPaginatedListViewModel) {
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
        dataSource.unbind()
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
        let numberCellRegistration = UICollectionView.CellRegistration<BottomNumberCell, KotlinInt> { cell, indexPath, value in
            cell.configure(number: value.intValue, index: indexPath.item)
        }

        let skeletonCellRegistration = UICollectionView.CellRegistration<SkeletonCell, Void> { _, _, _ in }

        dataSource = DeltaCollectionDataSource<KotlinInt>(
            collectionView: collectionView,
            cellProvider: { collectionView, indexPath, value in
                collectionView.dequeueConfiguredReusableCell(using: numberCellRegistration, for: indexPath, item: value)
            },
            loadingCellProvider: { collectionView, indexPath in
                collectionView.dequeueConfiguredReusableCell(using: skeletonCellRegistration, for: indexPath, item: ())
            }
        )

        // Anchor at the bottom as soon as the estimated size is known (the bottom rows are
        // skeletons that then fill in there). Dispatched async so it runs after the data source
        // has reloaded the collection view for the first snapshot.
        dataSource.onItemsChanged = { [weak self] _ in
            guard let self = self, !self.didInitialScroll, self.dataSource.totalSize > 1 else { return }
            self.didInitialScroll = true
            DispatchQueue.main.async { [weak self] in
                self?.scrollToBottom(animated: false)
            }
        }

        dataSource.bind(erased: viewModel.messages)
    }

    /// Scrolls to the last row. Used for the initial bottom anchor and after "add at bottom".
    func scrollToBottom(animated: Bool) {
        let count = dataSource.totalSize
        guard count > 0 else { return }
        collectionView.scrollToItem(at: IndexPath(item: count - 1, section: 0), at: .bottom, animated: animated)
    }
}

// MARK: - Number Cell

private class BottomNumberCell: UICollectionViewListCell {
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
        // Manually-added items use negative values; render them distinctly.
        if number < 0 {
            numberLabel.text = "Added #\(-number)"
            numberLabel.textColor = .tintColor
        } else {
            numberLabel.text = "#\(number)"
            numberLabel.textColor = .label
        }
        indexLabel.text = "index: \(index)"
    }
}

// MARK: - Skeleton Cell

/// A not-yet-loaded slot rendered as a skeleton item (no spinner, no text).
private class SkeletonCell: UICollectionViewListCell {
    private var bar: UIView!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        bar = UIView()
        bar.backgroundColor = .systemGray5
        bar.layer.cornerRadius = 6
        bar.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(bar)
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            bar.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            bar.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor),
            bar.widthAnchor.constraint(equalToConstant: 120),
            bar.heightAnchor.constraint(equalToConstant: 22)
        ])
    }
}

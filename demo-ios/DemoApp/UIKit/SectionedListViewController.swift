import UIKit
import DemoCore
import DeltaListCore

/// UIKit implementation of the sectioned list demo.
/// Uses SectionedDeltaCollectionDataSource from DeltaListCore - NO DiffableDataSource!
@MainActor
class SectionedListViewController: UIViewController {
    private let viewModel: SectionedListViewModel
    private var collectionView: UICollectionView!
    private var dataSource: DeltaListCore.SectionedDeltaCollectionDataSource<SectionHeader, Item>!

    var selectedSectionIndex: Int = -1 {
        didSet {
            if oldValue != selectedSectionIndex {
                collectionView?.reloadData()
            }
        }
    }

    var onSectionSelected: ((Int) -> Void)?

    init(viewModel: SectionedListViewModel) {
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

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        dataSource?.unbind()
    }

    private func setupCollectionView() {
        let layout = createLayout()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        view.addSubview(collectionView)

        // Register cell and header
        collectionView.register(SectionItemCell.self, forCellWithReuseIdentifier: "ItemCell")
        collectionView.register(
            SectionHeaderView.self,
            forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader,
            withReuseIdentifier: "Header"
        )
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        config.headerMode = .supplementary
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        // Create SectionedDeltaCollectionDataSource from DeltaListCore
        dataSource = DeltaListCore.SectionedDeltaCollectionDataSource<SectionHeader, Item>(
            collectionView: collectionView,
            cellProvider: { [weak self] (collectionView: UICollectionView, indexPath: IndexPath, item: Item) -> UICollectionViewCell in
                let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "ItemCell", for: indexPath) as! SectionItemCell
                cell.configure(with: item)
                return cell
            },
            headerProvider: { [weak self] (collectionView: UICollectionView, indexPath: IndexPath, header: SectionHeader) -> UICollectionReusableView in
                let headerView = collectionView.dequeueReusableSupplementaryView(
                    ofKind: UICollectionView.elementKindSectionHeader,
                    withReuseIdentifier: "Header",
                    for: indexPath
                ) as! SectionHeaderView

                let isSelected = indexPath.section == self?.selectedSectionIndex
                headerView.configure(with: header, isSelected: isSelected)
                headerView.setTapHandler { [weak self] in
                    self?.onSectionSelected?(indexPath.section)
                }
                return headerView
            }
        )

        // Bind to Kotlin Flow
        dataSource.bind(to: viewModel.sections)
    }

}

// MARK: - Section Header View

private class SectionHeaderView: UICollectionReusableView {
    private var titleLabel: UILabel!
    private var tapHandler: (() -> Void)?

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

        addSubview(titleLabel)
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            titleLabel.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12)
        ])

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tapGesture)
    }

    func configure(with header: SectionHeader, isSelected: Bool) {
        titleLabel.text = header.title
        // Convert ARGB Long to UIColor
        let argb = UInt64(header.color)
        let red = CGFloat((argb >> 16) & 0xFF) / 255.0
        let green = CGFloat((argb >> 8) & 0xFF) / 255.0
        let blue = CGFloat(argb & 0xFF) / 255.0
        backgroundColor = UIColor(red: red, green: green, blue: blue, alpha: isSelected ? 1.0 : 0.8)
    }

    func setTapHandler(_ handler: @escaping () -> Void) {
        self.tapHandler = handler
    }

    @objc private func handleTap() {
        tapHandler?()
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
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(with item: Item) {
        titleLabel.text = item.title
        idLabel.text = "ID: \(item.id.prefix(8))..."
    }
}

import UIKit
import DemoCore

/// UIKit implementation of the sectioned list demo.
/// Uses the DeltaRows DSL via DemoCore (not DeltaListCore directly — the SKIE-bundled Swift exists
/// in both, so importing both is ambiguous): typed `Header` and `Row` specs replace manual
/// registration and the cell/header provider switch.
@MainActor
class SectionedListViewController: UIViewController {
    private let viewModel: SectionedListViewModel
    private var collectionView: UICollectionView!
    private var dataSource: SectionedDeltaCollectionDataSource<AnyObject, AnyObject>!

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
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        config.headerMode = .supplementary
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        dataSource = collectionView.sections(viewModel.sections) {
            Header<SectionHeader, SectionHeaderView> { [weak self] view, header in
                guard let self else { return }
                let index = self.dataSource?.sections.firstIndex(where: { $0.header === header }) ?? -1
                view.configure(with: header, isSelected: index == self.selectedSectionIndex)
                view.setTapHandler { [weak self] in
                    self?.onSectionSelected?(index)
                }
            }
            Row<Item, SectionItemCell> { cell, item in
                cell.configure(with: item)
            }
        }
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

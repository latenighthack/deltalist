import UIKit
import DemoCore
import DeltaListCore

/// UIKit implementation of the sorted list demo using a 4-column UICollectionView grid.
/// Uses DeltaCollectionDataSource from DeltaListCore to apply the minimal changeset emitted by
/// `SortedListViewModel.profiles` (an unordered set projected into a sorted DeltaList).
@available(iOS 14.0, *)
@MainActor
class SortedListViewController: UIViewController {
    private let viewModel: SortedListViewModel
    private var collectionView: UICollectionView!
    private var dataSource: DeltaListCore.DeltaCollectionDataSource<DemoCore.Profile>!

    init(viewModel: SortedListViewModel) {
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
        dataSource?.unbind()
    }

    private func setupCollectionView() {
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: makeGridLayout())
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        view.addSubview(collectionView)
    }

    private func makeGridLayout() -> UICollectionViewLayout {
        let item = NSCollectionLayoutItem(
            layoutSize: NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0),
                heightDimension: .fractionalHeight(1.0)
            )
        )
        item.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4)

        let group = NSCollectionLayoutGroup.horizontal(
            layoutSize: NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0),
                heightDimension: .fractionalWidth(0.25)
            ),
            repeatingSubitem: item,
            count: 4
        )

        let section = NSCollectionLayoutSection(group: group)
        section.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
        return UICollectionViewCompositionalLayout(section: section)
    }

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<ProfileCell, DemoCore.Profile> { (cell, _, profile) in
            cell.configure(with: profile)
        }

        dataSource = DeltaListCore.DeltaCollectionDataSource<DemoCore.Profile>(
            collectionView: collectionView
        ) { (collectionView: UICollectionView, indexPath: IndexPath, profile: DemoCore.Profile) -> UICollectionViewCell in
            collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: profile)
        }

        // Tap a profile to remove it from the underlying set.
        dataSource.onItemSelected = { [viewModel] _, profile in
            viewModel.remove(profile: profile)
        }

        // Bind to the flow - the data source applies inserts/removes/moves automatically.
        dataSource.bind(erased: viewModel.profiles)
    }

    deinit {
        Task { @MainActor [dataSource] in
            dataSource?.unbind()
        }
    }
}

// MARK: - Profile Cell

@available(iOS 14.0, *)
private class ProfileCell: UICollectionViewCell {
    private let firstLabel = UILabel()
    private let lastLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        contentView.backgroundColor = .secondarySystemBackground
        contentView.layer.cornerRadius = 12
        contentView.layer.masksToBounds = true

        firstLabel.font = .preferredFont(forTextStyle: .body)
        firstLabel.textAlignment = .center
        firstLabel.adjustsFontSizeToFitWidth = true
        firstLabel.minimumScaleFactor = 0.7

        lastLabel.font = .preferredFont(forTextStyle: .caption1)
        lastLabel.textColor = .secondaryLabel
        lastLabel.textAlignment = .center
        lastLabel.adjustsFontSizeToFitWidth = true
        lastLabel.minimumScaleFactor = 0.7

        let stack = UIStackView(arrangedSubviews: [firstLabel, lastLabel])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 4),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -4)
        ])
    }

    func configure(with profile: DemoCore.Profile) {
        firstLabel.text = profile.firstName
        lastLabel.text = profile.lastName
    }
}

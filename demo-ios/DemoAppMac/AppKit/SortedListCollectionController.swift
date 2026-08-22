import AppKit
import DemoCore

/// AppKit sorted demo: the sorted profile projection rendered as a 4-column grid with
/// `DeltaNSCollectionDataSource`. Clicking a cell removes that profile (emitting a minimal delta).
@MainActor
final class SortedListCollectionController: NSViewController {
    private let viewModel: SortedListViewModel
    private var collectionView: NSCollectionView!
    private var dataSource: DeltaNSCollectionDataSource<Profile>!

    init(viewModel: SortedListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() { view = NSView() }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = installCollectionView(in: view, layout: makeGridLayout(columns: 4))
        collectionView.register(ProfileGridViewItem.self, forItemWithIdentifier: ProfileGridViewItem.reuseId)

        dataSource = DeltaNSCollectionDataSource<Profile>(
            collectionView: collectionView,
            itemProvider: { collectionView, indexPath, profile in
                let cell = collectionView.makeItem(withIdentifier: ProfileGridViewItem.reuseId, for: indexPath) as! ProfileGridViewItem
                cell.configure(profile: profile)
                return cell
            }
        )
        dataSource.onItemSelected = { [weak self] _, profile in
            self?.viewModel.remove(profile: profile)
        }
        dataSource.bind(erased: viewModel.profiles)
    }
}

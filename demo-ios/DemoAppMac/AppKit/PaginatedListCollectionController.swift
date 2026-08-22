import AppKit
import DemoCore

/// AppKit paginated demo: a flat soft/paginated list bound with `DeltaNSCollectionDataSource`.
/// Unloaded slots render a skeleton placeholder and trigger a fetch when they scroll into view.
@MainActor
final class PaginatedListCollectionController: NSViewController {
    private let viewModel: PaginatedListViewModel
    private var collectionView: NSCollectionView!
    private var dataSource: DeltaNSCollectionDataSource<DemoCore.KotlinInt>!

    init(viewModel: PaginatedListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() { view = NSView() }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = installCollectionView(in: view, layout: makeListLayout())
        collectionView.register(LabelCollectionViewItem.self, forItemWithIdentifier: LabelCollectionViewItem.reuseId)

        dataSource = DeltaNSCollectionDataSource<DemoCore.KotlinInt>(
            collectionView: collectionView,
            itemProvider: { collectionView, indexPath, number in
                let item = collectionView.makeItem(withIdentifier: LabelCollectionViewItem.reuseId, for: indexPath) as! LabelCollectionViewItem
                item.configure(title: "#\(number.intValue)", subtitle: "index: \(indexPath.item)")
                return item
            },
            loadingItemProvider: { collectionView, indexPath in
                let item = collectionView.makeItem(withIdentifier: LabelCollectionViewItem.reuseId, for: indexPath) as! LabelCollectionViewItem
                item.configure(title: "Loading…", subtitle: "")
                return item
            }
        )
        dataSource.bind(erased: viewModel.paginatedNumbers)
    }
}

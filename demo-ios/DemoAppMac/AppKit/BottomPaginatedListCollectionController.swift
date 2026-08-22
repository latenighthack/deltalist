import AppKit
import DemoCore

/// AppKit bottom-paginated ("chat-style") demo: a flat soft list bound with
/// `DeltaNSCollectionDataSource`. Unloaded slots render a skeleton and trigger a fetch on display.
@MainActor
final class BottomPaginatedListCollectionController: NSViewController {
    private let viewModel: BottomPaginatedListViewModel
    private var collectionView: NSCollectionView!
    private var dataSource: DeltaNSCollectionDataSource<DemoCore.KotlinInt>!

    init(viewModel: BottomPaginatedListViewModel) {
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
                let value = number.intValue
                // Manually-added items use negative values so they never collide with paginated data.
                let title = value < 0 ? "Added #\(-value)" : "#\(value)"
                item.configure(title: title, subtitle: "index: \(indexPath.item)")
                return item
            },
            loadingItemProvider: { collectionView, indexPath in
                let item = collectionView.makeItem(withIdentifier: LabelCollectionViewItem.reuseId, for: indexPath) as! LabelCollectionViewItem
                item.configure(title: "Loading…", subtitle: "")
                return item
            }
        )
        dataSource.bind(erased: viewModel.messages)
    }

    /// Scrolls to reveal the last row (used after "add at bottom").
    func scrollToBottom() {
        let count = dataSource.totalSize
        guard count > 0 else { return }
        collectionView.scrollToItems(at: [IndexPath(item: count - 1, section: 0)], scrollPosition: .bottom)
    }
}

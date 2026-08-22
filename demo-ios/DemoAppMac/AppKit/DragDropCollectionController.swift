import AppKit
import DemoCore

/// AppKit drag & drop demo: a `MoveableDeltaList` bound with `DeltaNSCollectionDataSource` plus the
/// demo-core `bind(moveable:draggingIn:)` extension, which owns the NSCollectionView drag lifecycle
/// and enforces the pinned/canMove policy through the Kotlin model.
@MainActor
final class DragDropCollectionController: NSViewController {
    private let viewModel: DragDropViewModel
    private var collectionView: NSCollectionView!
    private var dataSource: DeltaNSCollectionDataSource<AnyObject>!

    init(viewModel: DragDropViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() { view = NSView() }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = installCollectionView(in: view, layout: makeListLayout())
        collectionView.register(LabelCollectionViewItem.self, forItemWithIdentifier: LabelCollectionViewItem.reuseId)

        dataSource = DeltaNSCollectionDataSource<AnyObject>(
            collectionView: collectionView,
            itemProvider: { collectionView, indexPath, object in
                let cell = collectionView.makeItem(withIdentifier: LabelCollectionViewItem.reuseId, for: indexPath) as! LabelCollectionViewItem
                if let item = object as? Item {
                    let pinned = item.title.contains("Pinned")
                    cell.configure(
                        title: item.title,
                        subtitle: pinned ? "Cannot be moved" : "Drag to reorder",
                        tint: pinned ? NSColor.systemRed.withAlphaComponent(0.08) : nil
                    )
                }
                return cell
            }
        )
        dataSource.bind(moveable: viewModel.items, draggingIn: collectionView)
    }
}

import AppKit
import DemoCore

/// AppKit sectioned demo bound with `SectionedDeltaNSCollectionDataSource`. Colored supplementary
/// header views mark each section; tapping an item reports its section so the surrounding SwiftUI
/// controls can act on it.
@MainActor
final class SectionedListCollectionController: NSViewController {
    private let viewModel: SectionedListViewModel
    private var collectionView: NSCollectionView!
    private var dataSource: SectionedDeltaNSCollectionDataSource<SectionHeader, Item>!

    /// Reports the section of the most recently tapped item.
    var onSectionSelected: ((Int) -> Void)?

    init(viewModel: SectionedListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() { view = NSView() }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = installCollectionView(in: view, layout: makeListLayout(hasHeaders: true))
        collectionView.register(LabelCollectionViewItem.self, forItemWithIdentifier: LabelCollectionViewItem.reuseId)
        collectionView.register(
            SectionHeaderReusableView.self,
            forSupplementaryViewOfKind: NSCollectionView.elementKindSectionHeader,
            withIdentifier: SectionHeaderReusableView.reuseId
        )

        dataSource = SectionedDeltaNSCollectionDataSource<SectionHeader, Item>(
            collectionView: collectionView,
            itemProvider: { collectionView, indexPath, item in
                let cell = collectionView.makeItem(withIdentifier: LabelCollectionViewItem.reuseId, for: indexPath) as! LabelCollectionViewItem
                cell.configure(title: item.title, subtitle: "ID: \(item.id.prefix(8))…")
                return cell
            },
            headerProvider: { collectionView, indexPath, header in
                let view = collectionView.makeSupplementaryView(
                    ofKind: NSCollectionView.elementKindSectionHeader,
                    withIdentifier: SectionHeaderReusableView.reuseId,
                    for: indexPath
                ) as! SectionHeaderReusableView
                view.configure(title: header.title, color: .fromARGB(header.color))
                return view
            }
        )
        dataSource.onItemSelected = { [weak self] indexPath, _ in
            self?.onSectionSelected?(indexPath.section)
        }
        dataSource.bind(to: viewModel.sections)
    }
}

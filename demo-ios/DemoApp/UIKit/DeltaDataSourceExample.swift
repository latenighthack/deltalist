import UIKit
import DemoCore
import DeltaListCore

/// Example demonstrating DeltaCollectionDataSource with Kotlin types directly.
/// This shows the simplified API enabled by the Swift utilities bundled in DeltaListCore.
///
/// Before (manual diffable data source):
///   private var dataSource: UICollectionViewDiffableDataSource<Int, Int32>!
///   private func bindViewModel() { ... manual snapshot management ... }
///
/// After (with DeltaCollectionDataSource):
///   private var dataSource: DeltaCollectionDataSource<Item>!
///   dataSource.bind(to: viewModel.items)
@available(iOS 14.0, *)
@MainActor
class DeltaDataSourceExampleViewController: UIViewController {
    private let viewModel = ListViewModel()
    private var collectionView: UICollectionView!
    private var dataSource: StableDeltaCollectionDataSource<StableItem>!

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCollectionView()
        setupDataSource()
    }

    private func setupCollectionView() {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        let layout = UICollectionViewCompositionalLayout.list(using: config)

        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        view.addSubview(collectionView)
    }

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<UICollectionViewListCell, StableItem> { cell, indexPath, stableItem in
            var content = cell.defaultContentConfiguration()
            if let tickingItem = stableItem.value as? TickingItem {
                content.text = tickingItem.item.title
                content.secondaryText = "StableId: \(stableItem.stableId)"
            }
            cell.contentConfiguration = content
        }

        dataSource = StableDeltaCollectionDataSource(
            collectionView: collectionView,
            stableIdExtractor: { $0.stableId }
        ) { collectionView, indexPath, item in
            collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: item)
        }

        // One line to bind! No manual snapshot management.
        dataSource.bind(erased: viewModel.tickingItems)
    }

    deinit {
        dataSource?.unbind()
    }
}

import UIKit
import Combine
import DemoCore

/// UIKit implementation of the basic list demo using UICollectionView.
/// Uses the DeltaRows DSL via DemoCore (which exports DeltaListCore — importing both would make the
/// SKIE-bundled Swift ambiguous): one `Row` spec registers the cell, matches items by type, and
/// per-row tick-count state arrives via the app-installed `DeltaRowBinding.stateProvider`.
@available(iOS 14.0, *)
@MainActor
class BasicListViewController: UIViewController {
    private let viewModel: ListViewModel
    private var collectionView: UICollectionView!

    init(viewModel: ListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCollectionView()

        collectionView.items(viewModel.tickingItems) {
            Row<DemoCore.StableItem, TickingItemCell> { cell, stableItem in
                guard let tickingItem = stableItem.value as? TickingItem else { return }
                cell.configure(stableId: stableItem.stableId, tickingItem: tickingItem)
            }
        }
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

    func updateItems(_ items: [DemoCore.StableItem]) {
        // External updates are handled automatically by the data source binding
        // This method is kept for compatibility with the SwiftUI wrapper
    }
}

// MARK: - Ticking Item Cell

@available(iOS 14.0, *)
private class TickingItemCell: UICollectionViewListCell, ViewModelBoundCell {
    private var titleLabel: UILabel!
    private var tickLabel: UILabel!
    private var stableId: Int32 = 0

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
        stackView.spacing = 4
        stackView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        tickLabel = UILabel()
        tickLabel.font = .preferredFont(forTextStyle: .caption1)
        tickLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(titleLabel)
        stackView.addArrangedSubview(tickLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(stableId: Int32, tickingItem: TickingItem) {
        self.stableId = stableId
        titleLabel.text = tickingItem.item.title
        tickLabel.text = "Ticks: 0 | StableId: \(stableId)"
    }

    func viewModelStateDidChange(_ state: Any) {
        guard let count = state as? DemoCore.KotlinInt else { return }
        tickLabel.text = "Ticks: \(count.intValue) | StableId: \(stableId)"
    }
}

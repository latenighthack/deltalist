import AppKit
import DemoCore

/// AppKit basic-list demo using the DeltaRows DSL peer (`NSCollectionView.items { Row … }`) from
/// DeltaListCore. One `Row` spec registers the item and matches by type; per-row tick-count state
/// arrives via the app-installed `DeltaRowBinding.stateProvider` (see DemoAppMac.swift).
///
/// Imports only DemoCore (which re-exports DeltaListCore) - importing both would make the
/// SKIE-bundled Swift ambiguous.
@MainActor
final class BasicListCollectionController: NSViewController {
    private let viewModel: ListViewModel
    private var collectionView: NSCollectionView!

    init(viewModel: ListViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() {
        view = NSView()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = installCollectionView(in: view, layout: makeListLayout())

        collectionView.items(viewModel.tickingItems) {
            Row<DemoCore.StableItem, TickingCollectionViewItem> { item, stableItem in
                guard let ticking = stableItem.value as? TickingItem else { return }
                item.configure(stableId: stableItem.stableId, tickingItem: ticking)
            }
        }
    }
}

// MARK: - Ticking item

final class TickingCollectionViewItem: NSCollectionViewItem, ViewModelBoundCell {
    private let titleField = NSTextField(labelWithString: "")
    private let tickField = NSTextField(labelWithString: "")
    private var stableId: Int32 = 0

    override func loadView() {
        let container = NSView()
        titleField.font = .systemFont(ofSize: 13)
        tickField.font = .systemFont(ofSize: 11)
        tickField.textColor = .secondaryLabelColor

        let stack = NSStackView(views: [titleField, tickField])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
        ])
        self.view = container
    }

    func configure(stableId: Int32, tickingItem: TickingItem) {
        _ = view // ensure the view hierarchy is loaded (loadViewIfNeeded() is macOS 14+)
        self.stableId = stableId
        titleField.stringValue = tickingItem.item.title
        tickField.stringValue = "Ticks: 0 | StableId: \(stableId)"
    }

    @objc func viewModelStateDidChange(_ state: Any) {
        guard let count = state as? DemoCore.KotlinInt else { return }
        tickField.stringValue = "Ticks: \(count.intValue) | StableId: \(stableId)"
    }
}

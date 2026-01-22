import UIKit
import Combine

/// UIKit implementation of the drag and drop demo.
@MainActor
class DragDropViewController: UIViewController {
    private let viewModel: DragDropViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var cancellables = Set<AnyCancellable>()

    init(viewModel: DragDropViewModelAdapter) {
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
        setupDragAndDrop()
        bindViewModel()
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

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<DragDropCell, ItemWrapper> { [weak self] cell, indexPath, item in
            let canMove = self?.viewModel.canMove(item: item) ?? false
            cell.configure(with: item, canMove: canMove)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { [weak self] collectionView, indexPath, itemId in
            guard let self = self,
                  let item = self.viewModel.items.first(where: { $0.id == itemId }) else {
                return nil
            }
            return collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: item)
        }
    }

    private func setupDragAndDrop() {
        collectionView.dragDelegate = self
        collectionView.dropDelegate = self
        collectionView.dragInteractionEnabled = true
    }

    private func bindViewModel() {
        viewModel.$items
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.updateSnapshot(items: items)
            }
            .store(in: &cancellables)
    }

    private func updateSnapshot(items: [ItemWrapper]) {
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { $0.id }, toSection: 0)
        dataSource.apply(snapshot, animatingDifferences: true)
    }
}

// MARK: - UICollectionViewDragDelegate

extension DragDropViewController: UICollectionViewDragDelegate {
    func collectionView(_ collectionView: UICollectionView, itemsForBeginning session: UIDragSession, at indexPath: IndexPath) -> [UIDragItem] {
        guard indexPath.item < viewModel.items.count else { return [] }
        let item = viewModel.items[indexPath.item]

        // Don't allow dragging pinned items
        guard viewModel.canMove(item: item) else { return [] }

        _ = viewModel.beginDrag(at: indexPath.item)

        let itemProvider = NSItemProvider(object: item.id as NSString)
        let dragItem = UIDragItem(itemProvider: itemProvider)
        dragItem.localObject = item
        return [dragItem]
    }

    func collectionView(_ collectionView: UICollectionView, dragSessionDidEnd session: UIDragSession) {
        // Commit will be called by drop delegate
    }
}

// MARK: - UICollectionViewDropDelegate

extension DragDropViewController: UICollectionViewDropDelegate {
    func collectionView(_ collectionView: UICollectionView, dropSessionDidUpdate session: UIDropSession, withDestinationIndexPath destinationIndexPath: IndexPath?) -> UICollectionViewDropProposal {
        guard collectionView.hasActiveDrag else {
            return UICollectionViewDropProposal(operation: .forbidden)
        }

        if let destPath = destinationIndexPath {
            viewModel.updateDragPreview(to: destPath.item)
        }

        return UICollectionViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
    }

    func collectionView(_ collectionView: UICollectionView, performDropWith coordinator: UICollectionViewDropCoordinator) {
        Task {
            await viewModel.commitDrag()
        }
    }

    func collectionView(_ collectionView: UICollectionView, dropSessionDidEnd session: UIDropSession) {
        // Cleanup if needed
    }
}

// MARK: - Drag Drop Cell

private class DragDropCell: UICollectionViewListCell {
    private var handleImageView: UIImageView!
    private var titleLabel: UILabel!
    private var subtitleLabel: UILabel!
    private var canMove: Bool = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .horizontal
        stackView.spacing = 12
        stackView.alignment = .center
        stackView.translatesAutoresizingMaskIntoConstraints = false

        handleImageView = UIImageView(image: UIImage(systemName: "line.3.horizontal"))
        handleImageView.tintColor = .secondaryLabel
        handleImageView.setContentHuggingPriority(.required, for: .horizontal)

        let textStack = UIStackView()
        textStack.axis = .vertical
        textStack.spacing = 2

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        subtitleLabel = UILabel()
        subtitleLabel.font = .preferredFont(forTextStyle: .caption1)
        subtitleLabel.textColor = .secondaryLabel

        textStack.addArrangedSubview(titleLabel)
        textStack.addArrangedSubview(subtitleLabel)

        stackView.addArrangedSubview(handleImageView)
        stackView.addArrangedSubview(textStack)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(with item: ItemWrapper, canMove: Bool) {
        self.canMove = canMove
        titleLabel.text = item.title
        subtitleLabel.text = canMove ? "Long press to drag" : "Cannot be moved"
        handleImageView.isHidden = !canMove
        contentView.backgroundColor = canMove ? .clear : UIColor.systemRed.withAlphaComponent(0.1)
    }

    func setDragging(_ isDragging: Bool) {
        alpha = isDragging ? 0.7 : 1.0
        transform = isDragging ? CGAffineTransform(scaleX: 1.05, y: 1.05) : .identity
    }
}

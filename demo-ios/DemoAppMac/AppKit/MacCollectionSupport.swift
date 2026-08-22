import AppKit
import DemoCore

// MARK: - Layout helpers

/// A full-width, self-sizing list layout (rows span the width, height estimated from content).
/// Optional section headers pin to the top of each section.
@MainActor
func makeListLayout(hasHeaders: Bool = false) -> NSCollectionViewLayout {
    let itemSize = NSCollectionLayoutSize(
        widthDimension: .fractionalWidth(1.0),
        heightDimension: .estimated(48)
    )
    let item = NSCollectionLayoutItem(layoutSize: itemSize)
    let group = NSCollectionLayoutGroup.horizontal(layoutSize: itemSize, subitems: [item])
    let section = NSCollectionLayoutSection(group: group)
    if hasHeaders {
        let headerSize = NSCollectionLayoutSize(
            widthDimension: .fractionalWidth(1.0),
            heightDimension: .estimated(36)
        )
        let header = NSCollectionLayoutBoundarySupplementaryItem(
            layoutSize: headerSize,
            elementKind: NSCollectionView.elementKindSectionHeader,
            alignment: .top
        )
        section.boundarySupplementaryItems = [header]
    }
    return NSCollectionViewCompositionalLayout(section: section)
}

/// A fixed-count square grid (used by the Sorted demo).
@MainActor
func makeGridLayout(columns: Int) -> NSCollectionViewLayout {
    let itemSize = NSCollectionLayoutSize(
        widthDimension: .fractionalWidth(1.0 / CGFloat(columns)),
        heightDimension: .fractionalWidth(1.0 / CGFloat(columns))
    )
    let item = NSCollectionLayoutItem(layoutSize: itemSize)
    item.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4)
    let groupSize = NSCollectionLayoutSize(
        widthDimension: .fractionalWidth(1.0),
        heightDimension: .fractionalWidth(1.0 / CGFloat(columns))
    )
    let group = NSCollectionLayoutGroup.horizontal(layoutSize: groupSize, subitem: item, count: columns)
    let section = NSCollectionLayoutSection(group: group)
    section.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
    return NSCollectionViewCompositionalLayout(section: section)
}

/// Builds an `NSCollectionView` inside a scroll view pinned to `container`.
@MainActor
func installCollectionView(in container: NSView, layout: NSCollectionViewLayout) -> NSCollectionView {
    let scroll = NSScrollView()
    scroll.hasVerticalScroller = true
    scroll.drawsBackground = false
    scroll.translatesAutoresizingMaskIntoConstraints = false

    let collectionView = NSCollectionView()
    collectionView.collectionViewLayout = layout
    collectionView.isSelectable = true
    collectionView.backgroundColors = [.clear]

    scroll.documentView = collectionView
    container.addSubview(scroll)
    NSLayoutConstraint.activate([
        scroll.leadingAnchor.constraint(equalTo: container.leadingAnchor),
        scroll.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        scroll.topAnchor.constraint(equalTo: container.topAnchor),
        scroll.bottomAnchor.constraint(equalTo: container.bottomAnchor),
    ])
    return collectionView
}

// MARK: - Reusable items

/// A plain two-line (title + subtitle) collection item, used by several AppKit demos.
final class LabelCollectionViewItem: NSCollectionViewItem {
    static let reuseId = NSUserInterfaceItemIdentifier("LabelCollectionViewItem")

    private let titleField = NSTextField(labelWithString: "")
    private let subtitleField = NSTextField(labelWithString: "")
    private let box = NSView()

    override func loadView() {
        let container = NSView()

        titleField.font = .systemFont(ofSize: 13)
        titleField.lineBreakMode = .byTruncatingTail
        subtitleField.font = .systemFont(ofSize: 11)
        subtitleField.textColor = .secondaryLabelColor
        subtitleField.lineBreakMode = .byTruncatingTail

        let stack = NSStackView(views: [titleField, subtitleField])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false

        box.translatesAutoresizingMaskIntoConstraints = false
        box.wantsLayer = true
        container.addSubview(box)
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            box.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            box.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            box.topAnchor.constraint(equalTo: container.topAnchor),
            box.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
        ])
        self.view = container
    }

    func configure(title: String, subtitle: String, tint: NSColor? = nil) {
        _ = view // ensure the view hierarchy is loaded (loadViewIfNeeded() is macOS 14+)
        titleField.stringValue = title
        subtitleField.stringValue = subtitle
        subtitleField.isHidden = subtitle.isEmpty
        box.layer?.backgroundColor = (tint ?? .clear).cgColor
    }
}

/// A single centered-title item for the Sorted grid.
final class ProfileGridViewItem: NSCollectionViewItem {
    static let reuseId = NSUserInterfaceItemIdentifier("ProfileGridViewItem")

    private let firstField = NSTextField(labelWithString: "")
    private let lastField = NSTextField(labelWithString: "")

    override func loadView() {
        let container = NSView()
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.controlBackgroundColor.cgColor
        container.layer?.cornerRadius = 12

        firstField.font = .systemFont(ofSize: 13)
        firstField.alignment = .center
        lastField.font = .systemFont(ofSize: 11)
        lastField.textColor = .secondaryLabelColor
        lastField.alignment = .center

        let stack = NSStackView(views: [firstField, lastField])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor, constant: 4),
        ])
        self.view = container
    }

    func configure(profile: Profile) {
        _ = view // ensure the view hierarchy is loaded (loadViewIfNeeded() is macOS 14+)
        firstField.stringValue = profile.firstName
        lastField.stringValue = profile.lastName
    }
}

/// Section header supplementary view for the Sectioned demo.
final class SectionHeaderReusableView: NSView {
    static let reuseId = NSUserInterfaceItemIdentifier("SectionHeaderReusableView")

    private let titleField = NSTextField(labelWithString: "")

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        titleField.font = .boldSystemFont(ofSize: 13)
        titleField.textColor = .white
        titleField.translatesAutoresizingMaskIntoConstraints = false
        addSubview(titleField)
        NSLayoutConstraint.activate([
            titleField.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            titleField.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(title: String, color: NSColor) {
        titleField.stringValue = title
        layer?.backgroundColor = color.cgColor
    }
}

// MARK: - Color helper

extension NSColor {
    /// Builds a color from a Kotlin ARGB `Long` (as used by `SectionHeader.color`).
    static func fromARGB(_ argb: Int64) -> NSColor {
        let value = UInt64(bitPattern: argb)
        let red = CGFloat((value >> 16) & 0xFF) / 255.0
        let green = CGFloat((value >> 8) & 0xFF) / 255.0
        let blue = CGFloat(value & 0xFF) / 255.0
        return NSColor(srgbRed: red, green: green, blue: blue, alpha: 1.0)
    }
}

import Foundation
import SwiftUI
import DemoCore

// MARK: - Item Wrapper

/// Wraps a Kotlin Item for use in Swift.
/// Provides Identifiable conformance for SwiftUI lists.
struct ItemWrapper: Identifiable, Hashable {
    let id: String
    let title: String

    init(kotlinItem: Item) {
        self.id = kotlinItem.id
        self.title = kotlinItem.title
    }
}

// MARK: - Item Extension

/// Extension to make Kotlin Item directly usable as Identifiable.
/// Uses the underlying Kotlin id property.
extension Item {
    /// Provides access to the id for Identifiable conformance
    var itemId: String { self.id }
}

// MARK: - Drag State Wrapper

/// Drag state wrapper for Swift using SKIE's sealed class support.
enum DragStateWrapper: Equatable {
    case idle
    case dragging(item: ItemWrapper, fromIndex: Int, previewIndex: Int)
    case committing(item: ItemWrapper, fromIndex: Int, toIndex: Int)

    /// Initialize from Kotlin DragState using SKIE's onEnum pattern matching
    init(kotlinDragState: DragState<Item>) {
        switch onEnum(of: kotlinDragState) {
        case .idle:
            self = .idle
        case .dragging(let dragging):
            guard let item = dragging.item else {
                self = .idle
                return
            }
            self = .dragging(
                item: ItemWrapper(kotlinItem: item),
                fromIndex: Int(dragging.fromIndex),
                previewIndex: Int(dragging.previewIndex)
            )
        case .committing(let committing):
            guard let item = committing.item else {
                self = .idle
                return
            }
            self = .committing(
                item: ItemWrapper(kotlinItem: item),
                fromIndex: Int(committing.fromIndex),
                toIndex: Int(committing.toIndex)
            )
        }
    }

    /// Initialize from Any type (for backward compatibility)
    init(kotlinDragStateAny: Any) {
        // Check for DragStateIdle (singleton object)
        if kotlinDragStateAny is DragStateIdle {
            self = .idle
            return
        }

        if let dragging = kotlinDragStateAny as? DragStateDragging<Item>,
           let item = dragging.item {
            self = .dragging(
                item: ItemWrapper(kotlinItem: item),
                fromIndex: Int(dragging.fromIndex),
                previewIndex: Int(dragging.previewIndex)
            )
            return
        }

        if let committing = kotlinDragStateAny as? DragStateCommitting<Item>,
           let item = committing.item {
            self = .committing(
                item: ItemWrapper(kotlinItem: item),
                fromIndex: Int(committing.fromIndex),
                toIndex: Int(committing.toIndex)
            )
            return
        }

        self = .idle
    }
}

// MARK: - Section Types

/// Wraps a Kotlin SectionHeader for use in Swift.
struct SectionHeaderWrapper: Identifiable, Hashable {
    let id: String
    let title: String
    let color: Color

    init(kotlinHeader: SectionHeader) {
        self.id = kotlinHeader.title
        self.title = kotlinHeader.title
        // Convert ARGB Long to SwiftUI Color
        let argb = UInt64(kotlinHeader.color)
        let red = Double((argb >> 16) & 0xFF) / 255.0
        let green = Double((argb >> 8) & 0xFF) / 255.0
        let blue = Double(argb & 0xFF) / 255.0
        self.color = Color(red: red, green: green, blue: blue)
    }
}

/// Section wrapper containing a header and items.
struct ItemSectionWrapper: Identifiable {
    let id: String
    let header: SectionHeaderWrapper
    var items: [ItemWrapper]

    init?(kotlinSection: Any) {
        guard let section = kotlinSection as? DemoCore.Section<SectionHeader, Item>,
              let header = section.header else {
            return nil
        }
        self.header = SectionHeaderWrapper(kotlinHeader: header)
        self.id = self.header.id
        self.items = section.items.compactMap { item -> ItemWrapper? in
            guard let kotlinItem = item as? Item else { return nil }
            return ItemWrapper(kotlinItem: kotlinItem)
        }
    }
}

// MARK: - Ticking Item Wrapper

/// Wraps a Kotlin StableItem<TickingItem> for SwiftUI observation.
/// Uses SKIE's StateFlow→AsyncSequence conversion for tick count updates.
class TickingItemWrapper: ObservableObject, Identifiable {
    let stableId: Int32
    let item: Item

    @Published private(set) var tickCount: Int32 = 0

    private var tickTask: Task<Void, Never>?
    private let kotlinTickingItem: TickingItem
    private var isObserving: Bool = false

    var id: Int32 { stableId }

    init?(kotlinStableItem: StableItem) {
        self.stableId = kotlinStableItem.stableId

        guard let tickingItem = kotlinStableItem.value as? TickingItem else {
            return nil
        }

        self.kotlinTickingItem = tickingItem
        self.item = tickingItem.item

        startTickCollection()
    }

    private func startTickCollection() {
        guard !isObserving else { return }
        isObserving = true

        tickTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            // SKIE converts StateFlow to AsyncSequence automatically
            for await value in self.kotlinTickingItem.tickCount {
                if Task.isCancelled { break }
                self.tickCount = value.int32Value
            }
        }
    }

    /// Pause observation when scrolling off-screen
    func pauseObservation() {
        tickTask?.cancel()
        tickTask = nil
        isObserving = false
    }

    /// Resume observation when scrolling back on-screen
    func resumeObservation() {
        startTickCollection()
    }

    /// Fully stop the item (call when item is removed from list)
    func stop() {
        tickTask?.cancel()
        tickTask = nil
        isObserving = false
        kotlinTickingItem.stop()
    }

    deinit {
        tickTask?.cancel()
    }
}

import Foundation
import SwiftUI
import DemoCore

/// Drag state wrapper for Swift.
enum DragStateWrapper: Equatable {
    case idle
    case dragging(item: ItemWrapper, fromIndex: Int, previewIndex: Int)
    case committing(item: ItemWrapper, fromIndex: Int, toIndex: Int)

    init(kotlinDragState: Any) {
        // Check for DragStateIdle (singleton object)
        if kotlinDragState is DragStateIdle {
            self = .idle
            return
        }

        if let dragging = kotlinDragState as? DragStateDragging<Item>,
           let item = dragging.item {
            self = .dragging(
                item: ItemWrapper(kotlinItem: item),
                fromIndex: Int(dragging.fromIndex),
                previewIndex: Int(dragging.previewIndex)
            )
            return
        }

        if let committing = kotlinDragState as? DragStateCommitting<Item>,
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

/// Wraps the shared Kotlin DragDropViewModel for use in SwiftUI and UIKit.
@MainActor
class DragDropViewModelAdapter: ObservableObject {
    private let viewModel = DragDropViewModel()

    @Published private(set) var items: [ItemWrapper] = []
    @Published private(set) var dragState: DragStateWrapper = .idle

    private var itemsTask: Task<Void, Never>?
    private var dragStateTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect items from MoveableDeltaList
        itemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = ItemDeltaFlowCollector { [weak self] delta in
                guard let self = self else { return }
                self.items = delta.items.compactMap { item -> ItemWrapper? in
                    guard let kotlinItem = item as? Item else { return nil }
                    return ItemWrapper(kotlinItem: kotlinItem)
                }
            }

            do {
                try await self.viewModel.items.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }

        // Collect drag state
        dragStateTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = DragStateFlowCollector { [weak self] state in
                guard let self = self else { return }
                self.dragState = DragStateWrapper(kotlinDragState: state)
            }

            do {
                try await self.viewModel.items.dragState.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }
    }

    func stopCollecting() {
        itemsTask?.cancel()
        dragStateTask?.cancel()
    }

    // MARK: - Item Actions

    func addItem() {
        viewModel.addItem()
    }

    func addPinnedItem() {
        viewModel.addPinnedItem()
    }

    func clear() {
        viewModel.clear()
    }

    func reset() {
        viewModel.reset()
    }

    // MARK: - Drag Actions

    func canMove(item: ItemWrapper) -> Bool {
        !item.title.contains("Pinned")
    }

    func beginDrag(at index: Int) -> Bool {
        viewModel.items.beginDrag(index: Int32(index))
    }

    func updateDragPreview(to index: Int) {
        viewModel.items.updateDragPreview(toIndex: Int32(index))
    }

    func commitDrag() async -> Bool {
        do {
            let result = try await viewModel.items.commitDrag()
            return result.boolValue
        } catch {
            return false
        }
    }

    func cancelDrag() {
        viewModel.items.cancelDrag()
    }

    // MARK: - SwiftUI Drag & Drop Support

    func moveItem(from source: IndexSet, to destination: Int) {
        guard let fromIndex = source.first else { return }

        // Check for pinned items
        if fromIndex < items.count && items[fromIndex].title.contains("Pinned") {
            return
        }

        if destination < items.count && items[destination].title.contains("Pinned") {
            return
        }

        // Use the moveable API
        if viewModel.items.beginDrag(index: Int32(fromIndex)) {
            viewModel.items.updateDragPreview(toIndex: Int32(destination))
            Task {
                _ = await commitDrag()
            }
        }
    }

    deinit {
        itemsTask?.cancel()
        dragStateTask?.cancel()
    }
}

// MARK: - Flow Collectors

/// FlowCollector for DeltaList<Item> flows.
class ItemDeltaFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (Delta<Item>) -> Void

    init(onDelta: @escaping (Delta<Item>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? Delta<Item> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

/// FlowCollector for DragState flows.
class DragStateFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onState: (Any) -> Void

    init(onState: @escaping (Any) -> Void) {
        self.onState = onState
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let state = value {
            Task { @MainActor [self] in
                self.onState(state)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

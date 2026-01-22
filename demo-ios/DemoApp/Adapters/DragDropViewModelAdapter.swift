import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin DragDropViewModel for use in SwiftUI and UIKit.
/// Uses SKIE's automatic Flow→AsyncSequence conversion where available.
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
        // For MoveableDeltaList, we need to use the FlowCollector approach since
        // SKIE doesn't automatically convert protocol types to AsyncSequence
        itemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            // Create a collector that updates our items array
            let collector = DeltaItemCollector { [weak self] delta in
                guard let self = self else { return }
                self.items = delta.items.compactMap { item -> ItemWrapper? in
                    guard let kotlinItem = item as? Item else { return nil }
                    return ItemWrapper(kotlinItem: kotlinItem)
                }
            }

            // Use the async collect method
            do {
                try await self.viewModel.items.collect(collector: collector)
            } catch {
                // Flow completed or was cancelled
            }
        }

        // SKIE converts StateFlow to AsyncSequence automatically
        dragStateTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await state in self.viewModel.items.dragState {
                if Task.isCancelled { break }
                // Use the Any initializer since we don't have the exact generic type
                self.dragState = DragStateWrapper(kotlinDragStateAny: state)
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
            // SKIE converts suspend functions to async automatically
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
        // SwiftUI's destination is the index after the source is removed
        // When moving down, we need to adjust by -1 for the Kotlin API
        let adjustedDestination = fromIndex < destination ? destination - 1 : destination

        if viewModel.items.beginDrag(index: Int32(fromIndex)) {
            viewModel.items.updateDragPreview(toIndex: Int32(adjustedDestination))
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

// MARK: - Delta Collector

/// A FlowCollector implementation for Delta<Item> values.
/// Used when SKIE doesn't automatically convert a Flow to AsyncSequence (e.g., protocol types).
private final class DeltaItemCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: @MainActor @Sendable (Delta<Item>) -> Void

    init(onDelta: @escaping @MainActor @Sendable (Delta<Item>) -> Void) {
        self.onDelta = onDelta
    }

    // SKIE requires both methods, but the async one has a default implementation via extension
    // We only implement the callback version
    @nonobjc func __emit(value: Any?) async throws {
        guard let delta = value as? Delta<Item> else { return }
        let callback = onDelta
        await MainActor.run {
            callback(delta)
        }
    }

    func __emit(value: Any?, completionHandler: @escaping @Sendable ((any Error)?) -> Void) {
        guard let delta = value as? Delta<Item> else {
            completionHandler(nil)
            return
        }
        let callback = onDelta
        Task { @MainActor in
            callback(delta)
            completionHandler(nil)
        }
    }
}

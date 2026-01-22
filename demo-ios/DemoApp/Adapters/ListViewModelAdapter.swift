import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin ListViewModel for use in SwiftUI and UIKit.
@MainActor
class ListViewModelAdapter: ObservableObject {
    private let viewModel = ListViewModel()

    @Published private(set) var items: [ItemWrapper] = []
    @Published private(set) var tickingItems: [StableTickingItemWrapper] = []

    private var itemsTask: Task<Void, Never>?
    private var tickingItemsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect from items DeltaList
        itemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = DeltaFlowCollector<Item> { [weak self] delta in
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

        // Collect from tickingItems DeltaList
        tickingItemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = DeltaFlowCollector<StableItem> { [weak self] delta in
                guard let self = self else { return }
                self.tickingItems = delta.items.compactMap { item -> StableTickingItemWrapper? in
                    guard let stableItem = item as? StableItem else { return nil }
                    return StableTickingItemWrapper(kotlinStableItem: stableItem)
                }
            }

            do {
                try await self.viewModel.tickingItems.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }
    }

    func stopCollecting() {
        itemsTask?.cancel()
        tickingItemsTask?.cancel()
    }

    // MARK: - Actions

    func addItem() {
        viewModel.addItem()
    }

    func removeItem(at index: Int) {
        viewModel.removeItem(index: Int32(index))
    }

    func insertBefore(_ index: Int) {
        viewModel.insertBefore(index: Int32(index))
    }

    func insertAfter(_ index: Int) {
        viewModel.insertAfter(index: Int32(index))
    }

    func batchAdd() {
        viewModel.batchAdd()
    }

    func clear() {
        viewModel.clear()
    }

    deinit {
        itemsTask?.cancel()
        tickingItemsTask?.cancel()
    }
}

// MARK: - Wrapper Types

/// Wraps a Kotlin Item for use in Swift.
struct ItemWrapper: Identifiable, Hashable {
    let id: String
    let title: String

    init(kotlinItem: Item) {
        self.id = kotlinItem.id
        self.title = kotlinItem.title
    }
}

/// Wraps a Kotlin StableItem<TickingItem> for use in Swift.
class StableTickingItemWrapper: ObservableObject, Identifiable {
    let stableId: Int
    let item: ItemWrapper

    @Published private(set) var tickCount: Int = 0

    private var tickTask: Task<Void, Never>?
    private let kotlinTickingItem: TickingItem

    var id: Int { stableId }

    init?(kotlinStableItem: StableItem) {
        self.stableId = Int(kotlinStableItem.stableId)

        guard let tickingItem = kotlinStableItem.value as? TickingItem else {
            return nil
        }

        self.kotlinTickingItem = tickingItem
        self.item = ItemWrapper(kotlinItem: tickingItem.item)

        startTickCollection()
    }

    private func startTickCollection() {
        tickTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = StateFlowCollector<KotlinInt> { [weak self] value in
                guard let self = self else { return }
                self.tickCount = value.intValue
            }

            do {
                try await self.kotlinTickingItem.tickCount.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }
    }

    func stop() {
        tickTask?.cancel()
        kotlinTickingItem.stop()
    }

    deinit {
        tickTask?.cancel()
    }
}

// MARK: - Flow Collectors

/// Generic FlowCollector for DeltaList flows.
class DeltaFlowCollector<T: AnyObject>: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (Delta<T>) -> Void

    init(onDelta: @escaping (Delta<T>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? Delta<T> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

/// FlowCollector for StateFlow values.
class StateFlowCollector<T: AnyObject>: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: (T) -> Void

    init(onValue: @escaping (T) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let typedValue = value as? T {
            Task { @MainActor [self] in
                self.onValue(typedValue)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

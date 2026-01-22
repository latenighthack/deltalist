import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin ListViewModel for use in SwiftUI and UIKit.
/// Uses SKIE's automatic Flow→AsyncSequence conversion to eliminate FlowCollector boilerplate.
@MainActor
class ListViewModelAdapter: ObservableObject {
    let viewModel = ListViewModel()

    @Published private(set) var items: [ItemWrapper] = []
    @Published private(set) var tickingItems: [TickingItemWrapper] = []

    private var itemsTask: Task<Void, Never>?
    private var tickingItemsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // SKIE converts DeltaList (which is a Flow) to AsyncSequence automatically
        // No more FlowCollector classes needed!
        itemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            // Direct iteration over the Kotlin Flow via SKIE's AsyncSequence bridging
            for await delta in self.viewModel.items {
                if Task.isCancelled { break }
                // delta.items is a Kotlin List which bridges to Swift Array
                self.items = delta.items.compactMap { item -> ItemWrapper? in
                    guard let kotlinItem = item as? Item else { return nil }
                    return ItemWrapper(kotlinItem: kotlinItem)
                }
            }
        }

        tickingItemsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await delta in self.viewModel.tickingItems {
                if Task.isCancelled { break }
                self.tickingItems = delta.items.compactMap { stableItem -> TickingItemWrapper? in
                    guard let item = stableItem as? StableItem else { return nil }
                    return TickingItemWrapper(kotlinStableItem: item)
                }
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

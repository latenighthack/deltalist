import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin PaginatedListViewModel for use in SwiftUI and UIKit.
/// Uses SKIE's automatic Flow→AsyncSequence conversion to eliminate FlowCollector boilerplate.
@MainActor
class PaginatedListViewModelAdapter: ObservableObject {
    private let viewModel = PaginatedListViewModel()

    @Published private(set) var numbers: [Int] = []
    @Published private(set) var totalSize: Int = 0
    @Published private(set) var loadingDirection: DemoCore.LoadDirection? = nil
    @Published private(set) var loadedCount: Int = 0
    @Published private(set) var excludeDivisors: Set<Int> = []

    // Store the current delta to allow requesting more items
    private var currentDelta: Delta<KotlinInt>?

    private var numbersTask: Task<Void, Never>?
    private var loadingDirectionTask: Task<Void, Never>?
    private var loadedCountTask: Task<Void, Never>?
    private var excludeDivisorsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // SKIE converts Flows to AsyncSequence automatically
        numbersTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await delta in self.viewModel.paginatedNumbers {
                if Task.isCancelled { break }
                // Store delta so we can request more items later
                self.currentDelta = delta
                // Use delta.loadedItems() to safely get only loaded items without triggering bridging
                let loadedItems = delta.loadedItems()
                self.numbers = loadedItems.compactMap { ($0 as? NSNumber)?.intValue }
                self.totalSize = Int(delta.totalSize())
            }
        }

        // Collect loading direction using SKIE's StateFlow→AsyncSequence
        loadingDirectionTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await direction in self.viewModel.paginatedLoadingDirection {
                if Task.isCancelled { break }
                self.loadingDirection = direction
            }
        }

        // Collect loaded count
        loadedCountTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await count in self.viewModel.paginatedLoadedCount {
                if Task.isCancelled { break }
                self.loadedCount = count.intValue
            }
        }

        // Collect exclude divisors
        excludeDivisorsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await divisors in self.viewModel.excludeDivisors {
                if Task.isCancelled { break }
                self.excludeDivisors = Set(divisors.compactMap { ($0 as? NSNumber)?.intValue })
            }
        }
    }

    func stopCollecting() {
        numbersTask?.cancel()
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }

    // MARK: - Actions

    func toggleDivisorFilter(_ divisor: Int) {
        viewModel.toggleDivisorFilter(divisor: Int32(divisor))
    }

    /// Check if an item at the given index is loaded.
    func isLoadedAt(index: Int) -> Bool {
        currentDelta?.isLoadedAt(index: Int32(index)) ?? false
    }

    /// Get the loaded value at the given index, or nil if not loaded.
    func getLoadedItemAt(index: Int) -> Int? {
        guard let value = currentDelta?.getLoadedItemAt(index: Int32(index)) else { return nil }
        return (value as? NSNumber)?.intValue
    }

    /// Trigger loading at the given index. Call this when displaying a loading placeholder.
    func triggerLoadAt(index: Int) {
        currentDelta?.triggerLoadAt(index: Int32(index))
    }

    /// Returns true if there are more items to load beyond the current loaded items.
    var hasMoreItems: Bool {
        totalSize > numbers.count
    }

    deinit {
        numbersTask?.cancel()
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }
}

import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin BottomPaginatedListViewModel for the auxiliary state and actions that
/// sit AROUND the soft list (loading indicator, counts, filter toggles, add-at-top/bottom). The
/// soft list itself (`messages`) is bound with the consolidated `DeltaList` wrapper in the view.
@MainActor
class BottomPaginatedListViewModelAdapter: ObservableObject {
    let viewModel = BottomPaginatedListViewModel()

    @Published private(set) var loadingDirection: DemoCore.LoadDirection? = nil
    @Published private(set) var loadedCount: Int = 0
    @Published private(set) var excludeDivisors: Set<Int> = []

    private var loadingDirectionTask: Task<Void, Never>?
    private var loadedCountTask: Task<Void, Never>?
    private var excludeDivisorsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        loadingDirectionTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            for await direction in self.viewModel.loadingDirection {
                if Task.isCancelled { break }
                self.loadingDirection = direction
            }
        }

        loadedCountTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            for await count in self.viewModel.loadedCount {
                if Task.isCancelled { break }
                self.loadedCount = count.intValue
            }
        }

        excludeDivisorsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            for await divisors in self.viewModel.excludeDivisors {
                if Task.isCancelled { break }
                self.excludeDivisors = Set(divisors.compactMap { ($0 as? NSNumber)?.intValue })
            }
        }
    }

    // MARK: - Actions

    func addAtTop() {
        viewModel.addAtTop()
    }

    func addAtBottom() {
        viewModel.addAtBottom()
    }

    func toggleDivisorFilter(_ divisor: Int) {
        viewModel.toggleDivisorFilter(divisor: Int32(divisor))
    }

    deinit {
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }
}

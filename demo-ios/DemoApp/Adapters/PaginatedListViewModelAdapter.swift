import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin PaginatedListViewModel for use in SwiftUI and UIKit.
@MainActor
class PaginatedListViewModelAdapter: ObservableObject {
    private let viewModel = PaginatedListViewModel()

    @Published private(set) var numbers: [Int] = []
    @Published private(set) var loadingDirection: LoadDirection? = nil
    @Published private(set) var loadedCount: Int = 0
    @Published private(set) var excludeDivisors: Set<Int> = []

    private var numbersTask: Task<Void, Never>?
    private var loadingDirectionTask: Task<Void, Never>?
    private var loadedCountTask: Task<Void, Never>?
    private var excludeDivisorsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect paginated numbers
        numbersTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = IntDeltaFlowCollector { [weak self] delta in
                guard let self = self else { return }
                self.numbers = delta.items.compactMap { ($0 as? NSNumber)?.intValue }
            }

            do {
                try await self.viewModel.paginatedNumbers.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }

        // Collect loading direction
        loadingDirectionTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = OptionalStateFlowCollector<LoadDirection> { [weak self] value in
                guard let self = self else { return }
                self.loadingDirection = value
            }

            do {
                try await self.viewModel.paginatedLoadingDirection.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }

        // Collect loaded count
        loadedCountTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = StateFlowCollector<KotlinInt> { [weak self] value in
                guard let self = self else { return }
                self.loadedCount = value.intValue
            }

            do {
                try await self.viewModel.paginatedLoadedCount.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }

        // Collect exclude divisors
        excludeDivisorsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = SetStateFlowCollector { [weak self] value in
                guard let self = self else { return }
                self.excludeDivisors = Set(value.compactMap { ($0 as? NSNumber)?.intValue })
            }

            do {
                try await self.viewModel.excludeDivisors.collect(collector: collector)
            } catch {
                // Collection ended
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

    deinit {
        numbersTask?.cancel()
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }
}

// MARK: - Flow Collectors

/// FlowCollector for DeltaList<Int> flows.
class IntDeltaFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (Delta<KotlinInt>) -> Void

    init(onDelta: @escaping (Delta<KotlinInt>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? Delta<KotlinInt> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

/// FlowCollector for optional StateFlow values.
class OptionalStateFlowCollector<T: AnyObject>: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: (T?) -> Void

    init(onValue: @escaping (T?) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        Task { @MainActor [self] in
            self.onValue(value as? T)
            completionHandler(nil)
        }
    }
}

/// FlowCollector for Set StateFlow values.
class SetStateFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: (Set<AnyHashable>) -> Void

    init(onValue: @escaping (Set<AnyHashable>) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let kotlinSet = value as? Set<AnyHashable> {
            Task { @MainActor [self] in
                self.onValue(kotlinSet)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

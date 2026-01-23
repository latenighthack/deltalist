import SwiftUI
import Combine

/// Property wrapper that observes a DeltaList (Kotlin Flow<Delta<T>>) and provides its current state.
/// Equivalent to Android's collectAsDeltaState().
///
/// Usage:
/// ```swift
/// struct MyListView: View {
///     let viewModel = ListViewModel()
///     @DeltaState var delta: Item
///
///     init() {
///         _delta = DeltaState(viewModel.items)
///     }
///
///     var body: some View {
///         List($delta.items, id: \.id) { item in
///             Text(item.title)
///         }
///     }
/// }
/// ```
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@propertyWrapper
@MainActor
public struct DeltaState<T: AnyObject>: DynamicProperty {
    @StateObject private var observer: DeltaObserver<T>

    /// Initialize with a flow that emits Delta<T> values.
    /// The flow should be a Kotlin Flow (converted to AsyncSequence by SKIE).
    /// Uses runtime casting to handle SKIE module boundary type differences.
    public init<S: AsyncSequence>(_ flow: @autoclosure @escaping () -> S) {
        self._observer = StateObject(wrappedValue: DeltaObserver<T>(flow: flow))
    }

    public var wrappedValue: Delta<T> {
        observer.delta
    }

    public var projectedValue: DeltaObserver<T> {
        observer
    }
}

/// Internal observer that manages the flow collection lifecycle.
/// Uses runtime casting to handle SKIE module boundary type differences.
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@MainActor
public class DeltaObserver<T: AnyObject>: ObservableObject {
    @Published public private(set) var delta: Delta<T>
    @Published public private(set) var items: [T] = []
    @Published public private(set) var change: Change

    private var task: Task<Void, Never>?
    private var startFlow: (() -> Void)?
    private var isStarted = false

    init<S: AsyncSequence>(flow: @escaping () -> S) {
        self.delta = Delta(items: [], change: Change.Reload())
        self.change = Change.Reload()

        // Store the flow starter as a closure to avoid generic type issues
        self.startFlow = { [weak self] in
            guard let self = self else { return }
            self.task = Task { @MainActor [weak self] in
                guard let self = self else { return }
                do {
                    for try await value in flow() {
                        if Task.isCancelled { break }

                        // Try direct cast first, then try to extract from type-erased Delta
                        if let typedDelta = value as? Delta<T> {
                            self.delta = typedDelta
                            self.items = typedDelta.items as! [T]
                            self.change = typedDelta.change
                        } else if let anyDelta = value as? Delta<AnyObject> {
                            // Handle SKIE module boundary type erasure
                            let extractedItems = anyDelta.items.compactMap { $0 as? T }
                            self.items = extractedItems
                            self.change = anyDelta.change
                        }
                    }
                } catch {}
            }
        }
        start()
    }

    private func start() {
        guard !isStarted else { return }
        isStarted = true
        startFlow?()
    }

    public func stop() {
        task?.cancel()
        task = nil
        isStarted = false
    }

    public func restart() {
        stop()
        start()
    }

    deinit {
        task?.cancel()
    }
}

// NOTE: AnyDeltaAsyncSequence and AnyAsyncSequence have been removed.
// DeltaObserver now uses generic AsyncSequence directly with runtime casting,
// eliminating the need for type-erased wrappers.

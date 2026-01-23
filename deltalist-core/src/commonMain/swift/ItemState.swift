import SwiftUI
import Combine

/// Property wrapper that observes a StateFlow from an item with automatic lifecycle management.
/// Equivalent to Android's rememberItemState().
///
/// Usage:
/// ```swift
/// struct TickingItemRow: View {
///     let tickingItem: TickingItem
///     @ItemState var tickCount: Int32
///
///     init(tickingItem: TickingItem) {
///         self.tickingItem = tickingItem
///         _tickCount = ItemState(wrappedValue: 0, tickingItem.tickCount)
///     }
///
///     var body: some View {
///         VStack {
///             Text(tickingItem.item.title)
///             Text("Ticks: \(tickCount)")
///         }
///     }
/// }
/// ```
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@propertyWrapper
@MainActor
public struct ItemState<Value>: DynamicProperty {
    @StateObject private var observer: ItemStateObserver<Value>

    /// Initialize with an initial value and a flow that emits new values.
    /// Uses runtime casting to handle SKIE module boundary type differences.
    public init<S: AsyncSequence>(wrappedValue: Value, _ flow: @autoclosure @escaping () -> S) {
        self._observer = StateObject(wrappedValue: ItemStateObserver(
            initial: wrappedValue,
            flow: flow
        ))
    }

    public var wrappedValue: Value {
        observer.value
    }

    public var projectedValue: ItemStateObserver<Value> {
        observer
    }
}

/// Internal observer that manages the StateFlow collection lifecycle.
/// Uses runtime casting to handle SKIE module boundary type differences.
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@MainActor
public class ItemStateObserver<Value>: ObservableObject {
    @Published public private(set) var value: Value

    private var task: Task<Void, Never>?
    private var startFlow: (() -> Void)?
    private var isStarted = false

    public init<S: AsyncSequence>(initial: Value, flow: @escaping () -> S) {
        self.value = initial

        // Store the flow starter as a closure to avoid generic type issues
        self.startFlow = { [weak self] in
            guard let self = self else { return }
            self.task = Task { @MainActor [weak self] in
                guard let self = self else { return }
                do {
                    for try await newValue in flow() {
                        if Task.isCancelled { break }
                        // Try direct cast first, then try common conversions
                        if let v = newValue as? Value {
                            self.value = v
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

    /// Pause observation (call when view goes off-screen).
    public func pause() {
        task?.cancel()
        task = nil
        isStarted = false
    }

    /// Resume observation (call when view comes back on-screen).
    public func resume() {
        guard !isStarted else { return }
        start()
    }

    /// Stop and restart observation.
    public func restart() {
        pause()
        start()
    }

    deinit {
        task?.cancel()
    }
}

// NOTE: AnyValueAsyncSequence has been removed.
// ItemStateObserver now uses generic AsyncSequence directly with runtime casting,
// eliminating the need for type-erased wrappers.

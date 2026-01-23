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
    public init<S: AsyncSequence>(wrappedValue: Value, _ flow: @autoclosure @escaping () -> S) where S.Element == Value {
        self._observer = StateObject(wrappedValue: ItemStateObserver(
            initial: wrappedValue,
            flow: { AnyValueAsyncSequence(flow()) }
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
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@MainActor
public class ItemStateObserver<Value>: ObservableObject {
    @Published public private(set) var value: Value

    private var task: Task<Void, Never>?
    private let flowProvider: () -> AnyValueAsyncSequence<Value>
    private var isStarted = false

    init(initial: Value, flow: @escaping () -> AnyValueAsyncSequence<Value>) {
        self.value = initial
        self.flowProvider = flow
        start()
    }

    private func start() {
        guard !isStarted else { return }
        isStarted = true

        task = Task { @MainActor [weak self] in
            guard let self = self else { return }
            do {
                for try await newValue in self.flowProvider() {
                    if Task.isCancelled { break }
                    self.value = newValue
                }
            } catch {}
        }
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

/// Type-erased AsyncSequence for value types.
public struct AnyValueAsyncSequence<Value>: AsyncSequence {
    public typealias Element = Value
    public typealias AsyncIterator = AnyValueAsyncIterator<Value>

    private let makeIteratorClosure: () -> AnyValueAsyncIterator<Value>

    public init<S: AsyncSequence>(_ sequence: S) where S.Element == Value {
        makeIteratorClosure = {
            AnyValueAsyncIterator(sequence.makeAsyncIterator())
        }
    }

    public func makeAsyncIterator() -> AnyValueAsyncIterator<Value> {
        makeIteratorClosure()
    }
}

public struct AnyValueAsyncIterator<Value>: AsyncIteratorProtocol {
    public typealias Element = Value

    private let nextClosure: () async throws -> Value?

    init<I: AsyncIteratorProtocol>(_ iterator: I) where I.Element == Value {
        var iterator = iterator
        nextClosure = { try await iterator.next() }
    }

    public mutating func next() async throws -> Value? {
        try await nextClosure()
    }
}

import SwiftUI
import Combine

/// Property wrapper that observes a DeltaList (Kotlin Flow<Delta<T>>) and provides its current state.
/// Equivalent to Android's collectAsDeltaState().
///
/// Usage:
/// ```swift
/// struct MyListView: View {
///     let viewModel = ListViewModel()
///     @DeltaState var delta: Delta<Item>
///
///     init() {
///         _delta = DeltaState(viewModel.items)
///     }
///
///     var body: some View {
///         List(delta.items, id: \.id) { item in
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
    public init<S: AsyncSequence>(_ flow: @autoclosure @escaping () -> S) where S.Element == Delta<T> {
        self._observer = StateObject(wrappedValue: DeltaObserver<T>(flow: { AnyDeltaAsyncSequence(flow()) }))
    }

    public var wrappedValue: Delta<T> {
        observer.delta
    }

    public var projectedValue: DeltaObserver<T> {
        observer
    }
}

/// Internal observer that manages the flow collection lifecycle.
@available(iOS 14.0, macOS 11.0, tvOS 14.0, watchOS 7.0, *)
@MainActor
public class DeltaObserver<T: AnyObject>: ObservableObject {
    @Published public private(set) var delta: Delta<T>
    @Published public private(set) var items: [T] = []
    @Published public private(set) var change: Change

    private var task: Task<Void, Never>?
    private let flowProvider: () -> AnyDeltaAsyncSequence<T>
    private var isStarted = false

    init(flow: @escaping () -> AnyDeltaAsyncSequence<T>) {
        self.flowProvider = flow
        self.delta = Delta(items: [], change: Change.Reload())
        self.change = Change.Reload()
        start()
    }

    private func start() {
        guard !isStarted else { return }
        isStarted = true

        task = Task { @MainActor [weak self] in
            guard let self = self else { return }
            do {
                for try await newDelta in self.flowProvider() {
                    if Task.isCancelled { break }
                    self.delta = newDelta
                    self.items = newDelta.items as! [T]
                    self.change = newDelta.change
                }
            } catch {}
        }
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

/// Type-erased AsyncSequence for Delta types.
public struct AnyDeltaAsyncSequence<T: AnyObject>: AsyncSequence {
    public typealias Element = Delta<T>
    public typealias AsyncIterator = AnyDeltaAsyncIterator<T>

    private let makeIteratorClosure: () -> AnyDeltaAsyncIterator<T>

    public init<S: AsyncSequence>(_ sequence: S) where S.Element == Delta<T> {
        makeIteratorClosure = {
            AnyDeltaAsyncIterator(sequence.makeAsyncIterator())
        }
    }

    public func makeAsyncIterator() -> AnyDeltaAsyncIterator<T> {
        makeIteratorClosure()
    }
}

public struct AnyDeltaAsyncIterator<T: AnyObject>: AsyncIteratorProtocol {
    public typealias Element = Delta<T>

    private let nextClosure: () async throws -> Delta<T>?

    init<I: AsyncIteratorProtocol>(_ iterator: I) where I.Element == Delta<T> {
        var iterator = iterator
        nextClosure = { try await iterator.next() }
    }

    public mutating func next() async throws -> Delta<T>? {
        try await nextClosure()
    }
}

/// Type-erased AsyncSequence for any element type.
public struct AnyAsyncSequence<Element>: AsyncSequence {
    public typealias AsyncIterator = AnyAsyncIterator<Element>

    private let makeIteratorClosure: () -> AnyAsyncIterator<Element>

    public init<S: AsyncSequence>(_ sequence: S) where S.Element == Element {
        makeIteratorClosure = {
            AnyAsyncIterator(sequence.makeAsyncIterator())
        }
    }

    public func makeAsyncIterator() -> AnyAsyncIterator<Element> {
        makeIteratorClosure()
    }
}

public struct AnyAsyncIterator<Element>: AsyncIteratorProtocol {
    private let nextClosure: () async throws -> Element?

    init<I: AsyncIteratorProtocol>(_ iterator: I) where I.Element == Element {
        var iterator = iterator
        nextClosure = { try await iterator.next() }
    }

    public mutating func next() async throws -> Element? {
        try await nextClosure()
    }
}

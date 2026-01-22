import SwiftUI

/// Property wrapper for observing per-item state from an AsyncSequence.
/// Equivalent to Android's rememberItemState.
@propertyWrapper
@MainActor
public struct ItemState<T, State>: DynamicProperty {
    @StateObject private var observer: ItemStateObserver<T, State>

    public var wrappedValue: State {
        observer.state
    }

    public init(
        item: T,
        key: AnyHashable,
        initial: State,
        stateFlow: @escaping (T) -> AsyncStream<State>
    ) {
        _observer = StateObject(wrappedValue: ItemStateObserver(
            item: item,
            key: key,
            initial: initial,
            stateFlow: stateFlow
        ))
    }
}

/// ObservableObject that manages per-item state collection.
@MainActor
public class ItemStateObserver<T, State>: ObservableObject {
    @Published public private(set) var state: State

    private var task: Task<Void, Never>?
    private let item: T
    private let key: AnyHashable
    private let stateFlow: (T) -> AsyncStream<State>

    public init(
        item: T,
        key: AnyHashable,
        initial: State,
        stateFlow: @escaping (T) -> AsyncStream<State>
    ) {
        self.item = item
        self.key = key
        self.state = initial
        self.stateFlow = stateFlow
    }

    /// Starts collecting state. Called automatically by SwiftUI.
    public func start() {
        task?.cancel()
        task = Task { @MainActor in
            for await newState in stateFlow(item) {
                if Task.isCancelled { break }
                state = newState
            }
        }
    }

    /// Stops collecting state.
    public func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - View Extension for Item State

public extension View {
    /// Collects state from an item's AsyncStream and provides it to the view.
    /// - Parameters:
    ///   - item: The item to extract state from.
    ///   - key: A stable key for the item.
    ///   - initial: Initial state value before first emission.
    ///   - stateFlow: Function to extract the AsyncStream from the item.
    ///   - content: View builder that receives the current state.
    @ViewBuilder
    func withItemState<T, State, Content: View>(
        item: T,
        key: AnyHashable,
        initial: State,
        stateFlow: @escaping (T) -> AsyncStream<State>,
        @ViewBuilder content: @escaping (State) -> Content
    ) -> some View {
        ItemStateView(
            item: item,
            key: key,
            initial: initial,
            stateFlow: stateFlow,
            content: content
        )
    }
}

/// Internal view that manages item state observation.
private struct ItemStateView<T, State, Content: View>: View {
    let item: T
    let key: AnyHashable
    let initial: State
    let stateFlow: (T) -> AsyncStream<State>
    let content: (State) -> Content

    @State private var state: State
    @State private var task: Task<Void, Never>?

    init(
        item: T,
        key: AnyHashable,
        initial: State,
        stateFlow: @escaping (T) -> AsyncStream<State>,
        content: @escaping (State) -> Content
    ) {
        self.item = item
        self.key = key
        self.initial = initial
        self.stateFlow = stateFlow
        self.content = content
        self._state = State(initialValue: initial)
    }

    var body: some View {
        content(state)
            .task(id: key) {
                for await newState in stateFlow(item) {
                    state = newState
                }
            }
    }
}

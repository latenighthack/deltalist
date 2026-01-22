import SwiftUI

// MARK: - List Extensions for Soft Access

public extension Array {
    /// Returns the element at the specified index as a SoftValue.
    /// For regular arrays, always returns .present.
    /// This is useful for compatibility with SoftList-backed arrays.
    func softGet(at index: Int) -> SoftValue<Element>? {
        guard index >= 0 && index < count else { return nil }
        return .present(self[index])
    }
}

// MARK: - ForEach Helpers

/// Creates a ForEach view from IdentifiableItems.
public struct DeltaForEach<T, Content: View>: View {
    let items: [IdentifiableItem<T>]
    let content: (T, Int) -> Content

    public init(
        _ items: [IdentifiableItem<T>],
        @ViewBuilder content: @escaping (T, Int) -> Content
    ) {
        self.items = items
        self.content = content
    }

    public var body: some View {
        ForEach(items) { item in
            content(item.value, item.index)
        }
    }
}

// MARK: - View State Collection

public extension View {
    /// Collects delta state from an observer when the view appears.
    @MainActor
    func collectDeltaState<T, S: AsyncSequence>(
        into observer: DeltaListObserver<T>,
        from stream: S
    ) -> some View where S.Element == Delta<T> {
        self.onAppear {
            observer.start(from: stream)
        }
        .onDisappear {
            observer.stop()
        }
    }

    /// Collects delta state from an observer for StableItem types.
    @MainActor
    func collectDeltaState<T: StableItem, S: AsyncSequence>(
        into observer: StableDeltaListObserver<T>,
        from stream: S
    ) -> some View where S.Element == Delta<T> {
        self.onAppear {
            observer.start(from: stream)
        }
        .onDisappear {
            observer.stop()
        }
    }
}

// MARK: - Task-based State Collection

/// A view that collects delta state using a task and provides items to its content.
public struct DeltaStateView<T, Content: View>: View {
    @StateObject private var observer = DeltaListObserver<T>()
    let streamProvider: () -> AsyncStream<Delta<T>>
    let content: ([IdentifiableItem<T>], Change) -> Content

    public init(
        stream: @escaping @autoclosure () -> AsyncStream<Delta<T>>,
        @ViewBuilder content: @escaping ([IdentifiableItem<T>], Change) -> Content
    ) {
        self.streamProvider = stream
        self.content = content
    }

    public var body: some View {
        content(observer.items, observer.change)
            .task {
                observer.start(from: streamProvider())
            }
    }
}

// MARK: - Drag State Observation

/// ObservableObject for observing drag state.
@MainActor
public class DragStateObserver<T>: ObservableObject {
    @Published public private(set) var state: DragState<T> = .idle

    private var task: Task<Void, Never>?

    public init() {}

    /// Starts observing drag state from a stream.
    public func start(from stream: AsyncStream<DragState<T>>) {
        task?.cancel()
        task = Task { @MainActor in
            for await newState in stream {
                if Task.isCancelled { break }
                state = newState
            }
        }
    }

    /// Stops observing.
    public func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

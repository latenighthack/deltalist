import SwiftUI
import Combine

/// ObservableObject that wraps a DeltaList stream for use with SwiftUI.
/// Equivalent to Android's collectAsDeltaState.
@MainActor
public class DeltaListObserver<T>: ObservableObject {
    /// The current list of items with stable identifiers.
    @Published public private(set) var items: [IdentifiableItem<T>] = []

    /// The most recent change type.
    @Published public private(set) var change: Change = .reload

    /// Raw items without identifiable wrapper (for direct access).
    @Published public private(set) var rawItems: [T] = []

    private var task: Task<Void, Never>?
    private let shadowIdTracker = ShadowIdTracker<T>()
    private var lazyList: (any LazyList)?

    public init() {}

    /// Starts collecting from an AsyncSequence of deltas.
    /// - Parameter stream: The async sequence to collect from.
    public func start<S: AsyncSequence>(from stream: S) where S.Element == Delta<T> {
        stop()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    applyDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Starts collecting from an AsyncSequence with a LazyList for lifecycle management.
    public func start<S: AsyncSequence, L: LazyList>(from stream: S, lazyList: L) where S.Element == Delta<T>, L.Element == T {
        self.lazyList = lazyList
        start(from: stream)
    }

    /// Stops collecting and releases all lazy items.
    public func stop() {
        task?.cancel()
        task = nil
        lazyList?.releaseAll()
    }

    /// Releases a lazy item at the given index.
    public func releaseItem(at index: Int) {
        lazyList?.release(index: index)
    }

    private func applyDelta(_ delta: Delta<T>) {
        rawItems = delta.items
        change = delta.change
        items = shadowIdTracker.apply(delta: delta)
    }

    deinit {
        task?.cancel()
    }
}

/// Specialized observer for StableItem types that uses stable IDs directly.
@MainActor
public class StableDeltaListObserver<T: StableItem>: ObservableObject {
    /// The current list of items.
    @Published public private(set) var items: [T] = []

    /// The most recent change type.
    @Published public private(set) var change: Change = .reload

    private var task: Task<Void, Never>?
    private var lazyList: (any LazyList)?

    public init() {}

    /// Starts collecting from an AsyncSequence of deltas.
    public func start<S: AsyncSequence>(from stream: S) where S.Element == Delta<T> {
        stop()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    applyDelta(delta)
                }
            } catch {
                // Stream completed
            }
        }
    }

    /// Starts collecting with lazy list support.
    public func start<S: AsyncSequence, L: LazyList>(from stream: S, lazyList: L) where S.Element == Delta<T>, L.Element == T {
        self.lazyList = lazyList
        start(from: stream)
    }

    /// Stops collecting and releases all lazy items.
    public func stop() {
        task?.cancel()
        task = nil
        lazyList?.releaseAll()
    }

    /// Releases a lazy item at the given index.
    public func releaseItem(at index: Int) {
        lazyList?.release(index: index)
    }

    private func applyDelta(_ delta: Delta<T>) {
        items = delta.items
        change = delta.change
    }

    deinit {
        task?.cancel()
    }
}

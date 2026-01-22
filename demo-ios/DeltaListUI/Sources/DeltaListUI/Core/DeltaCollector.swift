import Foundation

/// Collects an AsyncSequence of Delta values and forwards them to a callback.
/// Manages the lifecycle of the collection task.
@MainActor
public class DeltaCollector<T> {
    private var task: Task<Void, Never>?
    private let stream: any AsyncSequence<Delta<T>, any Error>

    public init(stream: any AsyncSequence<Delta<T>, any Error>) {
        self.stream = stream
    }

    /// Starts collecting deltas from the stream.
    /// - Parameter onDelta: Called on the main actor when a new delta is received.
    public func start(onDelta: @escaping (Delta<T>) -> Void) {
        task?.cancel()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    onDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Stops collecting deltas and cancels any pending work.
    public func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

/// A simpler collector that works with non-throwing AsyncSequences.
@MainActor
public class SimpleDeltaCollector<T, S: AsyncSequence> where S.Element == Delta<T> {
    private var task: Task<Void, Never>?
    private let stream: S

    public init(stream: S) {
        self.stream = stream
    }

    /// Starts collecting deltas from the stream.
    public func start(onDelta: @escaping (Delta<T>) -> Void) {
        task?.cancel()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    onDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Stops collecting deltas.
    public func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

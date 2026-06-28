#if canImport(UIKit)
import UIKit

/// Extensions for DeltaCollectionDataSource to support MoveableDeltaList.
/// This file is in demo-core so it has access to DemoCore's types (MoveableDeltaList, FlowCollector, etc.)
/// which are re-exported by SKIE with module-specific names.

@available(iOS 14.0, *)
extension DeltaCollectionDataSource {

    /// Binds to a MoveableDeltaList and installs drag-and-drop reordering on `collectionView`.
    ///
    /// The binding layer owns the entire drag lifecycle so consumers never wire
    /// UICollectionViewDragDelegate/DropDelegate by hand. That ownership is what makes a whole
    /// class of stranding bugs impossible: every `beginDrag` is guaranteed to be terminated by
    /// exactly one `commitDrag`/`cancelDrag` (see `MoveableCollectionDragCoordinator`).
    ///
    /// Use this for MoveableDeltaList which doesn't get AsyncSequence conformance from SKIE.
    @MainActor
    public func bind(moveable: any MoveableDeltaList, draggingIn collectionView: UICollectionView) {
        unbind()

        // Funnel emissions through a single serial AsyncStream consumed in arrival order.
        // The previous implementation spawned a fresh `Task { @MainActor }` per emission;
        // unstructured main-actor tasks are not ordered relative to one another, so delta
        // N+1 could apply before N. Since each delta's mutations assume the prior delta
        // already landed, that desync corrupts state. A single `for await` loop guarantees
        // strict ordering, matching the serial `for try await` path used for typed streams.
        var continuationRef: AsyncStream<Any>.Continuation!
        let stream = AsyncStream<Any> { continuationRef = $0 }
        let continuation = continuationRef!

        let collector = MoveableFlowCollector { value in
            continuation.yield(value)
        }

        let collectTask = Task {
            do {
                try await moveable.collect(collector: collector)
            } catch {
                // Flow completed or was cancelled
            }
            continuation.finish()
        }

        // UICollectionView holds dragDelegate/dropDelegate weakly, so the coordinator must be
        // retained for the duration of the binding. Capture it strongly in the consuming task:
        // it then lives exactly as long as the binding is active and is released on unbind().
        let coordinator = MoveableCollectionDragCoordinator(moveable: moveable)
        collectionView.dragDelegate = coordinator
        collectionView.dropDelegate = coordinator
        collectionView.dragInteractionEnabled = true

        let task = Task { @MainActor [weak self, coordinator] in
            defer {
                collectTask.cancel()
                withExtendedLifetime(coordinator) {}
            }
            for await value in stream {
                guard let self = self else { break }
                self.apply(delta: value)
            }
        }

        // Store the task for cancellation
        setBindingTask(task)
    }
}

/// Owns the UICollectionView drag-and-drop lifecycle for a MoveableDeltaList.
///
/// The core invariant: while a drag is in flight the Kotlin model sits in `DragState.Dragging`,
/// and it MUST be moved out of that state by exactly one `commitDrag`/`cancelDrag`. If it isn't,
/// the model strands — the drag status flow never returns to Idle and every future `beginDrag`
/// is rejected.
///
/// The fragile case the hand-rolled demo got wrong: releasing a row at its original index. The
/// collection view then performs no drop, so neither `performDropWith` nor any drop-session
/// callback fires. `dragSessionDidEnd` is the single universal terminal — it fires for every
/// drag, success or not — so the safety net lives there.
@available(iOS 14.0, *)
@MainActor
private final class MoveableCollectionDragCoordinator: NSObject,
    UICollectionViewDragDelegate,
    UICollectionViewDropDelegate
{
    private let moveable: any MoveableDeltaList

    /// The index a drag started at. Non-nil means a drag is in flight in the Kotlin model and
    /// still owes a terminal commit/cancel. A completed drop clears this before the drag session
    /// ends, so the `dragSessionDidEnd` safety net no-ops for successful drops.
    private var activeDragIndex: Int?
    private var pendingDestination: Int?

    init(moveable: any MoveableDeltaList) {
        self.moveable = moveable
        super.init()
    }

    // MARK: - UICollectionViewDragDelegate

    func collectionView(_ collectionView: UICollectionView, itemsForBeginning session: UIDragSession, at indexPath: IndexPath) -> [UIDragItem] {
        // The Kotlin model owns the canMove policy: beginDrag returns false when the item is
        // locked or a drag is already running. Honoring that return value keeps the policy in one
        // place rather than duplicating it in every UI layer.
        guard moveable.beginDrag(index: Int32(indexPath.item)) else { return [] }
        activeDragIndex = indexPath.item
        pendingDestination = indexPath.item

        let dragItem = UIDragItem(itemProvider: NSItemProvider(object: "\(indexPath.item)" as NSString))
        return [dragItem]
    }

    func collectionView(_ collectionView: UICollectionView, dragSessionDidEnd session: UIDragSession) {
        // Terminal safety net for every drag, including the "released at the original index" case
        // where no drop is performed and the drop delegate never fires.
        finishWithoutDropIfNeeded()
    }

    // MARK: - UICollectionViewDropDelegate

    func collectionView(_ collectionView: UICollectionView, dropSessionDidUpdate session: UIDropSession, withDestinationIndexPath destinationIndexPath: IndexPath?) -> UICollectionViewDropProposal {
        guard collectionView.hasActiveDrag else {
            return UICollectionViewDropProposal(operation: .forbidden)
        }
        if let dest = destinationIndexPath {
            pendingDestination = dest.item
        }
        return UICollectionViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
    }

    func collectionView(_ collectionView: UICollectionView, performDropWith coordinator: UICollectionViewDropCoordinator) {
        guard let from = activeDragIndex else { return }
        let to = coordinator.destinationIndexPath?.item ?? pendingDestination ?? from

        // Clear before launching the async commit so the dragSessionDidEnd safety net no-ops.
        activeDragIndex = nil
        pendingDestination = nil

        // The model is the source of truth; it persists the move and emits the resulting delta,
        // which the bound data source applies. commitDrag(toIndex:) no-ops when from == to.
        Task { [moveable] in
            _ = try? await moveable.commitDrag(toIndex: Int32(to))
        }
    }

    // MARK: - Lifecycle

    private func finishWithoutDropIfNeeded() {
        guard activeDragIndex != nil else { return }
        activeDragIndex = nil
        pendingDestination = nil
        moveable.cancelDrag()
    }
}

/// FlowCollector implementation that forwards values to a callback.
/// Uses __emit (double underscore) as required by SKIE-generated FlowCollector protocol.
@available(iOS 14.0, *)
private final class MoveableFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: @Sendable (Any) -> Void

    init(onValue: @escaping @Sendable (Any) -> Void) {
        self.onValue = onValue
    }

    // Completion handler version (required by SKIE). Forwarding to the serial stream is
    // synchronous and ordered, so the next emission is processed only after this one is
    // enqueued in order.
    @objc func __emit(value: Any?, completionHandler: @escaping @Sendable ((any Error)?) -> Void) {
        if let value = value {
            onValue(value)
        }
        completionHandler(nil)
    }
}
#endif

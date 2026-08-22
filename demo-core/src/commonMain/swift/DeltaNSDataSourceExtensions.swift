#if canImport(AppKit) && !canImport(UIKit)
import AppKit

/// AppKit peer of DeltaDataSourceExtensions.swift (UIKit). Extends `DeltaNSCollectionDataSource` to
/// support `MoveableDeltaList` reordering on an `NSCollectionView`.
///
/// This file is in demo-core so it has access to DemoCore's types (MoveableDeltaList, FlowCollector,
/// etc.) which are re-exported by SKIE with module-specific names.
///
/// AppKit note: unlike UIKit (separate `dragDelegate`/`dropDelegate`), an `NSCollectionView` has a
/// single `delegate`. The data source installs itself there in its initializer, so the drag
/// coordinator takes over the delegate slot and forwards the data source's own delegate callbacks
/// (`willDisplay`/`didEndDisplaying`/`didSelectItemsAt`) back to it.

@available(macOS 11.0, *)
extension DeltaNSCollectionDataSource {

    /// Binds to a MoveableDeltaList and installs drag-and-drop reordering on `collectionView`.
    ///
    /// The binding layer owns the entire drag lifecycle so consumers never wire the
    /// NSCollectionViewDelegate drag/drop methods by hand. That ownership guarantees every
    /// `beginDrag` is terminated by exactly one `commitDrag`/`cancelDrag`
    /// (see `MoveableNSCollectionDragCoordinator`).
    ///
    /// Use this for MoveableDeltaList which doesn't get AsyncSequence conformance from SKIE.
    @MainActor
    public func bind(moveable: any MoveableDeltaList, draggingIn collectionView: NSCollectionView) {
        unbind()

        // Funnel emissions through a single serial AsyncStream consumed in arrival order, so
        // delta N+1 never applies before N (each delta's mutations assume the prior one landed).
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

        // NSCollectionView holds `delegate` weakly, so the coordinator must be retained for the
        // duration of the binding. Capture it strongly in the consuming task: it then lives exactly
        // as long as the binding is active and is released on unbind().
        let coordinator = MoveableNSCollectionDragCoordinator(moveable: moveable)
        coordinator.forwarding = self
        collectionView.delegate = coordinator
        collectionView.setDraggingSourceOperationMask(.move, forLocal: true)
        collectionView.registerForDraggedTypes([.string])

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

        setBindingTask(task)
    }
}

/// Owns the NSCollectionView drag-and-drop lifecycle for a MoveableDeltaList.
///
/// The core invariant: while a drag is in flight the Kotlin model sits in `DragState.Dragging`, and
/// it MUST be moved out of that state by exactly one `commitDrag`/`cancelDrag`. `draggingSession(
/// _:endedAt:dragOperation:)` is the single universal terminal - it fires for every drag, success
/// or not - so the safety net lives there.
@available(macOS 11.0, *)
@MainActor
private final class MoveableNSCollectionDragCoordinator: NSObject, NSCollectionViewDelegate {
    private let moveable: any MoveableDeltaList

    /// The data source keeps rendering/soft-loading/selection working while the coordinator holds
    /// the delegate slot. Held weakly - it is retained by the caller (the view controller).
    weak var forwarding: NSCollectionViewDelegate?

    /// The index a drag started at. Non-nil means a drag is in flight in the Kotlin model and still
    /// owes a terminal commit/cancel. A completed drop clears this before the drag session ends, so
    /// the `draggingSession(_:endedAt:)` safety net no-ops for successful drops.
    private var activeDragIndex: Int?
    private var pendingDestination: Int?

    init(moveable: any MoveableDeltaList) {
        self.moveable = moveable
        super.init()
    }

    // MARK: - Drag source

    func collectionView(_ collectionView: NSCollectionView, canDragItemsAt indexPaths: Set<IndexPath>, with event: NSEvent) -> Bool {
        // Policy (canMove/pinned) is enforced by the Kotlin model's beginDrag; allow the gesture
        // to start and veto the actual move at begin/commit time.
        return true
    }

    func collectionView(_ collectionView: NSCollectionView, pasteboardWriterForItemAt indexPath: IndexPath) -> NSPasteboardWriting? {
        return "\(indexPath.item)" as NSString
    }

    func collectionView(_ collectionView: NSCollectionView, draggingSession session: NSDraggingSession, willBeginAt screenPoint: NSPoint, forItemsAt indexPaths: Set<IndexPath>) {
        guard let index = indexPaths.min()?.item else { return }
        // The Kotlin model owns the canMove policy: beginDrag returns false when the item is locked
        // or a drag is already running. A false result means no active drag, so validateDrop forbids
        // the drop and no commit runs.
        if moveable.beginDrag(index: Int32(index)) {
            activeDragIndex = index
            pendingDestination = index
        } else {
            activeDragIndex = nil
        }
    }

    func collectionView(_ collectionView: NSCollectionView, draggingSession session: NSDraggingSession, endedAt screenPoint: NSPoint, dragOperation operation: NSDragOperation) {
        // Terminal safety net for every drag, including the "released at the original index" case
        // where no drop is accepted and acceptDrop never fires.
        finishWithoutDropIfNeeded()
    }

    // MARK: - Drop

    func collectionView(_ collectionView: NSCollectionView, validateDrop draggingInfo: NSDraggingInfo, proposedIndexPath proposedDropIndexPath: AutoreleasingUnsafeMutablePointer<NSIndexPath>, dropOperation proposedDropOperation: UnsafeMutablePointer<NSCollectionView.DropOperation>) -> NSDragOperation {
        guard activeDragIndex != nil else { return [] }
        proposedDropOperation.pointee = .before
        pendingDestination = proposedDropIndexPath.pointee.item
        return .move
    }

    func collectionView(_ collectionView: NSCollectionView, acceptDrop draggingInfo: NSDraggingInfo, indexPath: IndexPath, dropOperation: NSCollectionView.DropOperation) -> Bool {
        guard let from = activeDragIndex else { return false }
        let to = indexPath.item

        // Clear before launching the async commit so the endedAt safety net no-ops.
        activeDragIndex = nil
        pendingDestination = nil

        // The model is the source of truth; it persists the move and emits the resulting delta,
        // which the bound data source applies. commitDrag(toIndex:) no-ops when from == to.
        Task { [moveable] in
            _ = try? await moveable.commitDrag(toIndex: Int32(to))
        }
        _ = from
        return true
    }

    // MARK: - Forwarded delegate callbacks (keep the data source's behavior intact)

    func collectionView(_ collectionView: NSCollectionView, willDisplay item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        forwarding?.collectionView?(collectionView, willDisplay: item, forRepresentedObjectAt: indexPath)
    }

    func collectionView(_ collectionView: NSCollectionView, didEndDisplaying item: NSCollectionViewItem, forRepresentedObjectAt indexPath: IndexPath) {
        forwarding?.collectionView?(collectionView, didEndDisplaying: item, forRepresentedObjectAt: indexPath)
    }

    func collectionView(_ collectionView: NSCollectionView, didSelectItemsAt indexPaths: Set<IndexPath>) {
        forwarding?.collectionView?(collectionView, didSelectItemsAt: indexPaths)
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
/// Uses __emit (double underscore) as required by the SKIE-generated FlowCollector protocol.
@available(macOS 11.0, *)
private final class MoveableFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: @Sendable (Any) -> Void

    init(onValue: @escaping @Sendable (Any) -> Void) {
        self.onValue = onValue
    }

    @objc func __emit(value: Any?, completionHandler: @escaping @Sendable ((any Error)?) -> Void) {
        if let value = value {
            onValue(value)
        }
        completionHandler(nil)
    }
}
#endif

package com.latenighthack.deltalist

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State of an ongoing drag operation.
 */
sealed class DragState<out T> {
    /**
     * No drag in progress.
     */
    data object Idle : DragState<Nothing>()

    /**
     * User is actively dragging an item.
     */
    data class Dragging<T>(
        val item: T,
        val fromIndex: Int,
        val previewIndex: Int
    ) : DragState<T>()

    /**
     * Drag has been released and we're waiting for the move to be persisted.
     */
    data class Committing<T>(
        val item: T,
        val fromIndex: Int,
        val toIndex: Int,
        val confirmed: Boolean = false
    ) : DragState<T>()
}

/**
 * A [DeltaList] wrapper that enables drag-and-drop reordering.
 *
 * This wrapper provides optimistic reordering during drag - items visually
 * move as the user drags. On drop, the [onMove] callback is invoked to persist
 * the change. If persistence fails, the list reverts to its original order,
 * or to the latest authoritative snapshot if the source changed meanwhile.
 * Unrelated source edits during a gesture are retained; ending that gesture
 * restores the source snapshot instead of committing stale positional indices.
 *
 * Example:
 * ```
 * val todos = repository.observeTodos().moveable(
 *     canMove = { todo, from, to -> !todo.isPinned },
 *     onMove = { todo, from, to ->
 *         repository.moveTodo(todo.id, to)
 *     }
 * )
 * ```
 */
interface MoveableDeltaList<T> : DeltaList<T> {
    /**
     * Current drag state. Observe this to render drag indicators and loading states.
     */
    val dragState: StateFlow<DragState<T>>

    /**
     * Begin dragging an item at the given index.
     *
     * @return true if drag started, false if the item cannot be dragged
     *         (e.g., [canMove] returned false, or another drag is in progress)
     */
    fun beginDrag(index: Int): Boolean

    /**
     * Update the preview position during drag.
     * This immediately updates the list for visual feedback.
     *
     * @param toIndex The index to show the dragged item at
     */
    fun updateDragPreview(toIndex: Int)

    /**
     * Commit the current drag, persisting the move via the [onMove] callback.
     *
     * On success, returns true. The list remains in the new order.
     * On failure, returns false and the list reverts to the original order.
     *
     * @return true if the move was successfully persisted, false otherwise
     */
    suspend fun commitDrag(): Boolean

    /**
     * Commit the drag to a specific destination index.
     *
     * Use this when the UI framework (e.g., UICollectionView) handles drag preview
     * animations and you only need to commit the final position without emitting
     * intermediate preview updates.
     *
     * @param toIndex The final destination index
     * @return true if the move was successfully persisted, false otherwise
     */
    suspend fun commitDrag(toIndex: Int): Boolean

    /**
     * Cancel the current drag without committing.
     * The list reverts to the original order before the drag started.
     */
    fun cancelDrag()
}

/**
 * Wrap this [DeltaList] to enable drag-and-drop reordering.
 *
 * @param canMove Optional predicate to determine if a move is allowed.
 *                Called when drag starts and during drag.
 *                If null, all moves are allowed.
 * @param onMove Suspend callback invoked when a drag is committed.
 *               Should persist the move (e.g., to a database) and return true on success.
 */
fun <T> DeltaList<T>.moveable(
    canMove: ((item: T, fromIndex: Int, toIndex: Int) -> Boolean)? = null,
    onMove: suspend (item: T, fromIndex: Int, toIndex: Int) -> Boolean
): MoveableDeltaList<T> = MoveableDeltaListImpl(this, canMove, onMove)

/**
 * Implementation of [MoveableDeltaList]. Active observers share one upstream
 * subscription and display history; the last observer cancels that subscription.
 * The cached display remains available for drag operations between subscriptions.
 */
internal class MoveableDeltaListImpl<T>(
    private val upstream: DeltaList<T>,
    private val canMove: ((item: T, fromIndex: Int, toIndex: Int) -> Boolean)?,
    private val onMove: suspend (item: T, fromIndex: Int, toIndex: Int) -> Boolean
) : MoveableDeltaList<T> {
    private val _dragState = MutableStateFlow<DragState<T>>(DragState.Idle)
    override val dragState: StateFlow<DragState<T>> = _dragState.asStateFlow()

    private data class CurrentDisplay<T>(
        val delta: Delta<T>,
        val sessionId: Long,
        val failure: Throwable? = null,
    )

    // Every displayed change, whether local or authoritative, has one publication
    // history. A slow observer reloads instead of applying a move twice or missing
    // a rollback that raced with the next source update.
    private val display = DeltaState<CurrentDisplay<T>?>(null) { current ->
        current?.copy(delta = Delta(current.delta.items, Change.Reload))
    }

    private inner class Session(val id: Long, val scope: CoroutineScope) {
        var subscribers = 0
        lateinit var sourceJob: Job
    }

    private val sessions = Mutex()
    private var activeSession: Session? = null
    private var nextSessionId = 0L

    private class PendingCommit<T>(
        val from: Int,
        val to: Int,
        val after: SoftList<T>,
        val displayedFrom: Int,
        var applied: Boolean,
        var confirmed: Boolean = false,
        var discarded: Boolean = false,
    )

    // A successful callback can return before its source emission is collected.
    // Keep all outstanding confirmations independently of the public drag state.
    private val pending = mutableListOf<PendingCommit<T>>()
    private var sourceHistoryLost = false
    private var currentCommit: PendingCommit<T>? = null
    private var latestSource: Delta<T>? = null
    private var preDropItems: List<T>? = null
    private var originalDragIndex = -1
    private var dragConflicted = false

    override fun beginDrag(index: Int): Boolean {
        if (_dragState.value !is DragState.Idle) return false
        val items = display.value?.delta?.items ?: return false
        if (index !in 0 until items.size) return false
        val loaded = items.softLoadedItems()
        // Moveable lists are fully loaded; never reinterpret placeholder coordinates.
        if (loaded.size != items.size) return false
        val item = loaded[index]
        if (canMove?.invoke(item, index, index) == false) return false
        preDropItems = loaded
        originalDragIndex = index
        dragConflicted = false
        _dragState.value = DragState.Dragging(item, index, index)
        return true
    }

    override fun updateDragPreview(toIndex: Int) {
        val current = _dragState.value as? DragState.Dragging ?: return
        val items = display.value?.delta?.items ?: return
        val destination = toIndex.coerceIn(0, items.size - 1)
        if (destination == current.previewIndex) return
        if (canMove?.invoke(current.item, current.previewIndex, destination) == false) return
        val reordered = items.softLoadedItems().toMutableList()
        reordered.add(destination, reordered.removeAt(current.previewIndex))
        publish(Delta(reordered, Change.Mutations(listOf(Mutation.Move(current.previewIndex, destination)))))
        _dragState.value = current.copy(previewIndex = destination)
    }

    override suspend fun commitDrag(): Boolean {
        val current = _dragState.value as? DragState.Dragging ?: return false
        return commit(current, current.previewIndex)
    }

    override suspend fun commitDrag(toIndex: Int): Boolean {
        val current = _dragState.value as? DragState.Dragging ?: return false
        val size = display.value?.delta?.items?.size ?: return false
        val destination = toIndex.coerceIn(0, size - 1)
        if (canMove?.invoke(current.item, originalDragIndex, destination) == false) {
            revert()
            return false
        }
        return commit(current, destination)
    }

    private suspend fun commit(current: DragState.Dragging<T>, destination: Int): Boolean {
        // Indices from before an unrelated source edit are no longer safe to persist.
        if (dragConflicted) {
            revert()
            return false
        }
        val from = originalDragIndex
        if (from == destination) {
            if (current.previewIndex != from) revert() else cleanup()
            return true
        }
        val after = if (current.previewIndex == destination) display.value!!.delta.items
            else preDropItems!!.toMutableList().apply { add(destination, removeAt(from)) }.asSoftList()
        val record = PendingCommit(from, destination, after, current.previewIndex,
            applied = current.previewIndex == destination)
        pending.add(record)
        if (pending.size > MAX_PENDING_COMMITS) {
            pending.removeAt(0)
            // A callback need not publish upstream at all. Bound retained snapshots;
            // the next authoritative update must reload after lost acknowledgment history.
            sourceHistoryLost = true
        }
        currentCommit = record
        _dragState.value = DragState.Committing(current.item, from, destination)
        return try {
            if (onMove(current.item, from, destination)) {
                if (!record.applied && !record.discarded) {
                    // Native drag previews do not change the model. Publish exactly
                    // one final move after success if the source has not done so yet.
                    publish(Delta(record.after, Change.Mutations(listOf(
                        Mutation.Move(record.displayedFrom, record.to)))))
                    record.applied = true
                }
                cleanup()
                true
            } else {
                pending.remove(record)
                revert(record.confirmed)
                false
            }
        } catch (error: CancellationException) {
            pending.remove(record)
            revert(record.confirmed)
            throw error
        } catch (error: Exception) {
            pending.remove(record)
            revert(record.confirmed)
            false
        }
    }

    override fun cancelDrag() {
        if (_dragState.value is DragState.Dragging) revert()
    }

    private fun revert(alreadyConfirmed: Boolean = false) {
        // A callback can fail or be cancelled after the source has committed. Its
        // authoritative result must not be replaced with the old drag baseline.
        if (alreadyConfirmed) dragConflicted = true
        val items = if (dragConflicted) {
            discardPending()
            latestSource?.items
        } else preDropItems?.asSoftList()
        if (items != null) publish(Delta(items, Change.Reload))
        cleanup()
    }

    private fun cleanup() {
        _dragState.value = DragState.Idle
        preDropItems = null
        originalDragIndex = -1
        currentCommit = null
        dragConflicted = false
    }

    private fun discardPending() {
        pending.forEach { it.discarded = true }
        pending.clear()
        sourceHistoryLost = false
    }

    private fun publish(delta: Delta<T>) {
        display.value = CurrentDisplay(delta, activeSession?.id ?: 0L)
    }

    private fun sameItems(left: SoftList<T>, right: SoftList<T>): Boolean {
        if (left === right) return true
        if (left.size != right.size) return false
        for (index in 0 until left.size) {
            val a = left.softGet(index) as? SoftValue.Present ?: return false
            val b = right.softGet(index) as? SoftValue.Present ?: return false
            if (a.value != b.value) return false
        }
        return true
    }

    /** Number of consecutive pending moves acknowledged by this source publication. */
    private fun confirmations(delta: Delta<T>): Int {
        if (pending.isEmpty()) return 0
        // Reload explicitly resets source identity (including when confirmations
        // were conflated). Equal values are not evidence of the same logical rows.
        if (delta.change is Change.Reload) return 0
        val operations = (delta.change as Change.Mutations).operations
        var operation = 0
        var count = 0
        while (count < pending.size && operation < operations.size) {
            val record = pending[count]
            val first = operations[operation]
            if (first is Mutation.Move && first.count == 1 && first.fromIndex == record.from && first.toIndex == record.to) {
                operation++
            } else if (first is Mutation.Remove && first.count == 1 && first.index == record.from) {
                val second = operations.getOrNull(operation + 1) as? Mutation.Insert ?: break
                if (second.count != 1 || second.index != record.to) break
                operation += 2
            } else break
            count++
        }
        return if (count > 0 && operation == operations.size && sameItems(delta.items, pending[count - 1].after)) count else 0
    }

    private fun acceptSource(session: Session, delta: Delta<T>, first: Boolean) {
        if (activeSession !== session) return
        val previousSource = latestSource
        val state = _dragState.value
        // Recollection replays the source baseline. It must not overwrite an
        // outstanding preview or masquerade as acknowledgment of an equal-valued move.
        if (first && !sourceHistoryLost && previousSource != null && previousSource.items === delta.items &&
            (pending.isNotEmpty() || state is DragState.Dragging)) {
            display.value?.let { publish(Delta(it.delta.items, Change.Reload)) }
            return
        }
        latestSource = delta
        val confirmed = if (dragConflicted || sourceHistoryLost) 0 else confirmations(delta)
        if (confirmed > 0) {
            val acknowledged = pending.take(confirmed)
            repeat(confirmed) { pending.removeAt(0) }
            val missing = acknowledged.filterNot { it.applied }.map { record ->
                record.applied = true
                Mutation.Move(record.displayedFrom, record.to)
            }
            acknowledged.forEach { it.confirmed = true }
            val current = _dragState.value as? DragState.Committing
            if (current != null && acknowledged.any { it === currentCommit }) {
                _dragState.value = current.copy(confirmed = true)
            }
            if (pending.isEmpty() && state !is DragState.Dragging) {
                publish(Delta(delta.items, Change.Mutations(missing)))
            } else if (first) {
                display.value?.let { publish(Delta(it.delta.items, Change.Reload)) }
            }
            return
        }
        if (state is DragState.Dragging) {
            // Preserve the gesture's display until it ends, but remember the newest
            // authoritative snapshot for rollback rather than losing these updates.
            dragConflicted = true
            if (first) display.value?.let { publish(Delta(it.delta.items, Change.Reload)) }
            return
        }
        val hadOptimisticMoves = pending.isNotEmpty() || sourceHistoryLost
        if (hadOptimisticMoves) discardPending()
        if (state is DragState.Committing) dragConflicted = true
        publish(if (first || hadOptimisticMoves) Delta(delta.items, Change.Reload) else delta)
    }

    private suspend fun acquireSession(): Session {
        val context = currentCoroutineContext()
        return sessions.withLock {
            val session = activeSession ?: Session(++nextSessionId,
                CoroutineScope(context.minusKey(Job) + SupervisorJob())).also { created ->
                activeSession = created
                created.sourceJob = created.scope.launch(start = CoroutineStart.LAZY) {
                    var first = true
                    try {
                        upstream.collect { delta ->
                            acceptSource(created, delta, first)
                            first = false
                        }
                    } catch (error: Throwable) {
                        if (activeSession === created && created.scope.isActive) {
                            val current = display.value?.delta ?: Delta(emptyList<T>(), Change.Reload)
                            display.value = CurrentDisplay(current, created.id, error)
                        }
                    }
                }
            }
            session.subscribers++
            session
        }
    }

    private companion object {
        const val MAX_PENDING_COMMITS = 64
    }

    override suspend fun collect(collector: FlowCollector<Delta<T>>) {
        val session = acquireSession()
        session.sourceJob.start()
        var delivered = false
        try {
            display.collect { current ->
                if (current != null && current.sessionId == session.id) {
                    current.failure?.let { throw it }
                    collector.emit(if (delivered) current.delta else Delta(current.delta.items, Change.Reload))
                    delivered = true
                }
            }
        } finally {
            withContext(NonCancellable) {
                val close = sessions.withLock {
                    session.subscribers--
                    if (session.subscribers == 0) {
                        if (activeSession === session) activeSession = null
                        true
                    } else false
                }
                if (close) session.scope.cancel()
            }
        }
    }
}

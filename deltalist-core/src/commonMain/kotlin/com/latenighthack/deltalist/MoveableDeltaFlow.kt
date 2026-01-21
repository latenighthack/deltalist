package com.latenighthack.deltalist

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

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
        val toIndex: Int
    ) : DragState<T>()
}

/**
 * A [DeltaFlow] wrapper that enables drag-and-drop reordering.
 *
 * This wrapper provides optimistic reordering during drag - items visually
 * move as the user drags. On drop, the [onMove] callback is invoked to persist
 * the change. If persistence fails, the list reverts to its original order.
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
interface MoveableDeltaFlow<T> : DeltaFlow<T> {
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
     * Cancel the current drag without committing.
     * The list reverts to the original order before the drag started.
     */
    fun cancelDrag()
}

/**
 * Wrap this [DeltaFlow] to enable drag-and-drop reordering.
 *
 * @param canMove Optional predicate to determine if a move is allowed.
 *                Called when drag starts and during drag.
 *                If null, all moves are allowed.
 * @param onMove Suspend callback invoked when a drag is committed.
 *               Should persist the move (e.g., to a database) and return true on success.
 */
fun <T> DeltaFlow<T>.moveable(
    canMove: ((item: T, fromIndex: Int, toIndex: Int) -> Boolean)? = null,
    onMove: suspend (item: T, fromIndex: Int, toIndex: Int) -> Boolean
): MoveableDeltaFlow<T> = MoveableDeltaFlowImpl(this, canMove, onMove)

/**
 * Implementation of [MoveableDeltaFlow].
 */
internal class MoveableDeltaFlowImpl<T>(
    private val upstream: DeltaFlow<T>,
    private val canMove: ((item: T, fromIndex: Int, toIndex: Int) -> Boolean)?,
    private val onMove: suspend (item: T, fromIndex: Int, toIndex: Int) -> Boolean
) : MoveableDeltaFlow<T> {

    private val _dragState = MutableStateFlow<DragState<T>>(DragState.Idle)
    override val dragState: StateFlow<DragState<T>> = _dragState.asStateFlow()

    // The current list state (may be reordered during drag)
    private val _currentDelta = MutableStateFlow<Delta<T>?>(null)

    // Snapshot of list before drag started (for revert on cancel/failure)
    private var preDropItems: List<T>? = null

    // Original index when drag started (for tracking the net move)
    private var originalDragIndex: Int = -1

    override fun beginDrag(index: Int): Boolean {
        val currentDelta = _currentDelta.value ?: return false

        // Can't start a new drag while one is in progress
        if (_dragState.value !is DragState.Idle) return false

        // Check bounds
        if (index < 0 || index >= currentDelta.items.size) return false

        val item = currentDelta.items[index]

        // Check if this item can be moved
        if (canMove != null && !canMove.invoke(item, index, index)) return false

        // Snapshot current state for potential revert
        preDropItems = currentDelta.items.toList()
        originalDragIndex = index

        _dragState.value = DragState.Dragging(item, fromIndex = index, previewIndex = index)
        return true
    }

    override fun updateDragPreview(toIndex: Int) {
        val current = _dragState.value
        if (current !is DragState.Dragging) return

        val currentDelta = _currentDelta.value ?: return

        // Clamp to valid range
        val clampedIndex = toIndex.coerceIn(0, maxOf(0, currentDelta.items.size - 1))

        // No change needed
        if (clampedIndex == current.previewIndex) return

        // Check if this move would be allowed
        if (canMove != null && !canMove.invoke(current.item, current.previewIndex, clampedIndex)) {
            return
        }

        // Perform the visual reorder
        val newItems = currentDelta.items.toMutableList()
        val item = newItems.removeAt(current.previewIndex)
        newItems.add(clampedIndex, item)

        // Emit the reordered list with a Move mutation
        _currentDelta.value = Delta(
            newItems,
            Change.Mutations(listOf(Mutation.Move(current.previewIndex, clampedIndex)))
        )

        _dragState.value = current.copy(previewIndex = clampedIndex)
    }

    override suspend fun commitDrag(): Boolean {
        val current = _dragState.value
        if (current !is DragState.Dragging) return false

        val fromIndex = originalDragIndex
        val toIndex = current.previewIndex

        // No-op if dropped in the same position
        if (fromIndex == toIndex) {
            cleanup()
            return true
        }

        // Transition to committing state
        _dragState.value = DragState.Committing(current.item, fromIndex, toIndex)

        return try {
            val success = onMove(current.item, fromIndex, toIndex)
            if (success) {
                cleanup()
                true
            } else {
                revert()
                false
            }
        } catch (e: Exception) {
            revert()
            false
        }
    }

    override fun cancelDrag() {
        val current = _dragState.value
        if (current is DragState.Dragging) {
            revert()
        }
    }

    private fun revert() {
        val original = preDropItems
        if (original != null) {
            _currentDelta.value = Delta(original, Change.Reload)
        }
        cleanup()
    }

    private fun cleanup() {
        _dragState.value = DragState.Idle
        preDropItems = null
        originalDragIndex = -1
    }

    override suspend fun collect(collector: FlowCollector<Delta<T>>) {
        val upstreamFlow = upstream.map { delta ->
            val currentState = _dragState.value
            when {
                currentState is DragState.Idle -> {
                    _currentDelta.value = delta
                    delta
                }
                currentState is DragState.Committing -> {
                    // After commit, accept upstream state
                    _currentDelta.value = delta
                    delta
                }
                else -> null // During Dragging, ignore upstream
            }
        }.filterNotNull()

        val dragFlow = _currentDelta.filterNotNull().filter {
            _dragState.value is DragState.Dragging
        }

        merge(upstreamFlow, dragFlow).collect { delta ->
            collector.emit(delta)
        }
    }
}

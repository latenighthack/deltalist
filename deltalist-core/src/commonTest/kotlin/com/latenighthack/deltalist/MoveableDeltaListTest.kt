package com.latenighthack.deltalist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoveableDeltaListTest {

    @Test
    fun `beginDrag starts drag and updates state`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        // Collect once to populate currentItems
        moveable.first()

        assertTrue(moveable.beginDrag(1))

        val state = moveable.dragState.value
        assertIs<DragState.Dragging<String>>(state)
        assertEquals("B", state.item)
        assertEquals(1, state.fromIndex)
        assertEquals(1, state.previewIndex)
    }

    @Test
    fun `beginDrag returns false for invalid index`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        moveable.first()

        assertFalse(moveable.beginDrag(-1))
        assertFalse(moveable.beginDrag(5))
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `beginDrag returns false when canMove returns false`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable(
            canMove = { item, _, _ -> item != "B" },
            onMove = { _, _, _ -> true }
        )

        moveable.first()

        assertFalse(moveable.beginDrag(1)) // B cannot be moved
        assertTrue(moveable.beginDrag(0))  // A can be moved
    }

    @Test
    fun `updateDragPreview updates preview index`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        moveable.first()
        moveable.beginDrag(0)
        moveable.updateDragPreview(2)

        val state = moveable.dragState.value
        assertIs<DragState.Dragging<String>>(state)
        assertEquals(0, state.fromIndex)
        assertEquals(2, state.previewIndex)
    }

    @Test
    fun `updateDragPreview clamps to valid range`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        moveable.first()
        moveable.beginDrag(1)

        moveable.updateDragPreview(-5)
        assertEquals(0, (moveable.dragState.value as DragState.Dragging).previewIndex)

        moveable.updateDragPreview(100)
        assertEquals(2, (moveable.dragState.value as DragState.Dragging).previewIndex)
    }

    @Test
    fun `cancelDrag returns to Idle`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        moveable.first()
        moveable.beginDrag(1)
        moveable.updateDragPreview(0)

        moveable.cancelDrag()

        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `commitDrag calls onMove and returns to Idle`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        var moveCallArgs: Triple<String, Int, Int>? = null

        val moveable = source.moveable { item, from, to ->
            moveCallArgs = Triple(item, from, to)
            true
        }

        moveable.first()
        moveable.beginDrag(2) // C
        moveable.updateDragPreview(0)

        val success = moveable.commitDrag()

        assertTrue(success)
        assertEquals(Triple("C", 2, 0), moveCallArgs)
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `commitDrag returns false when onMove fails`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> false }

        moveable.first()
        moveable.beginDrag(0)
        moveable.updateDragPreview(2)

        val success = moveable.commitDrag()

        assertFalse(success)
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `commitDrag returns true without calling onMove when dropped in same position`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        var moveCalled = false

        val moveable = source.moveable { _, _, _ ->
            moveCalled = true
            true
        }

        moveable.first()
        moveable.beginDrag(1)
        // Don't update preview - stays at original index

        val success = moveable.commitDrag()

        assertTrue(success)
        assertFalse(moveCalled)
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `commitDrag(toIndex) returns true without calling onMove when target is same position`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        var moveCalled = false

        val moveable = source.moveable { _, _, _ ->
            moveCalled = true
            true
        }

        moveable.first()
        moveable.beginDrag(1)

        val success = moveable.commitDrag(toIndex = 1)

        assertTrue(success)
        assertFalse(moveCalled)
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `commitDrag handles exception from onMove`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ ->
            throw RuntimeException("Network error")
        }

        moveable.first()
        moveable.beginDrag(0)
        moveable.updateDragPreview(2)

        val success = moveable.commitDrag()

        assertFalse(success)
        assertIs<DragState.Idle>(moveable.dragState.value)
    }

    @Test
    fun `cannot start new drag while one is in progress`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moveable = source.moveable { _, _, _ -> true }

        moveable.first()
        assertTrue(moveable.beginDrag(0))
        assertFalse(moveable.beginDrag(1))

        // First drag should still be active
        val state = moveable.dragState.value
        assertIs<DragState.Dragging<String>>(state)
        assertEquals("A", state.item)
    }

    @Test
    fun `commitDrag shows Committing state during callback`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val statesDuringCommit = mutableListOf<DragState<String>>()
        lateinit var moveable: MoveableDeltaList<String>

        moveable = source.moveable { _, _, _ ->
            statesDuringCommit.add(moveable.dragState.value)
            true
        }

        moveable.first()
        moveable.beginDrag(0)
        moveable.updateDragPreview(2)
        moveable.commitDrag()

        assertEquals(1, statesDuringCommit.size)
        val committingState = statesDuringCommit[0]
        assertIs<DragState.Committing<String>>(committingState)
        assertEquals("A", committingState.item)
        assertEquals(0, committingState.fromIndex)
        assertEquals(2, committingState.toIndex)
    }

    @Test
    fun `commitDrag restores Idle when cancelled mid-commit`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val onMoveEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val moveable = source.moveable { _, _, _ ->
            onMoveEntered.complete(Unit)
            release.await() // suspend inside the commit until the coroutine is cancelled
            true
        }

        moveable.first()
        moveable.beginDrag(0)
        moveable.updateDragPreview(2)

        val job = launch { moveable.commitDrag() }
        onMoveEntered.await()
        assertIs<DragState.Committing<String>>(moveable.dragState.value)

        // Cancelling mid-save must not strand the list in Committing, which would reject
        // every future beginDrag.
        job.cancelAndJoin()

        assertIs<DragState.Idle>(moveable.dragState.value)
        assertTrue(moveable.beginDrag(0))
    }

    @Test
    fun `upstream changes update currentItems`() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B"))
        val moveable = source.moveable { _, _, _ -> true }

        // Initial collect
        moveable.first()

        // Add item upstream
        source.append("C")
        moveable.first() // Collect the update

        // Should now be able to drag the new item
        assertTrue(moveable.beginDrag(2))
        assertEquals("C", (moveable.dragState.value as DragState.Dragging).item)
    }
}

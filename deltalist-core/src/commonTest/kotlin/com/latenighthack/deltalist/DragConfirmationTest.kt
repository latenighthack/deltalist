@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.assertFlatOracle
import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

class DragConfirmationTest {
    @Test fun synchronousAndDelayedAcknowledgmentsPreserveBothCollectorsIds() = runTest {
        for (confirmation in 0..2) {
            val source = mutableDeltaListOf(listOf("A", "B", "C"))
            val moving = source.moveable { _, from, to ->
                if (confirmation != 2) source.move(from, to)
                if (confirmation == 1) yield()
                true
            }
            val histories = List(2) { mutableListOf<Delta<StableItem<String>>>() }
            val jobs = histories.map { history -> backgroundScope.launch {
                moving.withStableIds().collect { history += it }
            } }
            runCurrent()
            val ids = histories.map { it.last().items.softLoadedItems().map { item -> item.stableId } }
            moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
            assertTrue(moving.commitDrag()); runCurrent()
            if (confirmation == 2) { source.move(0, 2); runCurrent() }
            histories.forEachIndexed { index, history ->
                assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems().map { it.value })
                assertEquals(listOf(ids[index][1], ids[index][2], ids[index][0]),
                    history.last().items.softLoadedItems().map { it.stableId })
                history.assertFlatOracle()
            }
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun consecutivePendingCommitsDoNotUndoLaterPreviews() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<StableItem<String>>>()
        backgroundScope.launch { moving.withStableIds().collect { history += it } }
        runCurrent()
        val ids = history.last().items.softLoadedItems().map { it.stableId }
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        assertTrue(moving.commitDrag())
        moving.beginDrag(0); moving.updateDragPreview(1); runCurrent()
        assertTrue(moving.commitDrag())
        source.move(0, 2); runCurrent()
        assertEquals(listOf("C", "B", "A"), history.last().items.softLoadedItems().map { it.value })
        source.move(0, 1); runCurrent()
        assertEquals(listOf(ids[2], ids[1], ids[0]), history.last().items.softLoadedItems().map { it.stableId })
        history.assertFlatOracle()
    }

    @Test fun deleteInsertAcknowledgmentDoesNotReplaceMovedIds() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, from, to ->
            source.update { it.add(to, it.removeAt(from)) }
            true
        }
        val history = mutableListOf<Delta<StableItem<String>>>()
        backgroundScope.launch { moving.withStableIds().collect { history += it } }
        runCurrent()
        val ids = history.last().items.softLoadedItems().map { it.stableId }
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        moving.commitDrag(); runCurrent()
        assertEquals(listOf(ids[1], ids[2], ids[0]), history.last().items.softLoadedItems().map { it.stableId })
        history.assertFlatOracle()
    }

    @Test fun overflowedAcknowledgmentHistoryReloadsInsteadOfReplayingAnOldMove() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { moving.collect { history += it } }
        runCurrent()
        repeat(70) {
            assertTrue(moving.beginDrag(0))
            moving.updateDragPreview(2); runCurrent()
            assertTrue(moving.commitDrag())
        }
        source.move(0, 2); runCurrent()
        assertIs<Change.Reload>(history.last().change)
        assertEquals(source.value, history.last().items.softLoadedItems())
        history.assertFlatOracle()
    }

    @Test fun nativeCommitPublishesExactlyOneFinalMoveWithOrWithoutEarlierPreview() = runTest {
        for (preview in listOf(false, true)) for (sourceBeforeReturn in listOf(false, true)) {
            val source = mutableDeltaListOf(listOf("A", "B", "C"))
            val moving = source.moveable { _, from, to ->
                if (sourceBeforeReturn) { source.move(from, to); yield() }
                true
            }
            val history = mutableListOf<Delta<StableItem<String>>>()
            val job = backgroundScope.launch { moving.withStableIds().collect { history += it } }
            runCurrent()
            val ids = history.last().items.softLoadedItems().map { it.stableId }
            moving.beginDrag(0)
            if (preview) { moving.updateDragPreview(1); runCurrent() }
            assertTrue(moving.commitDrag(2)); runCurrent()
            assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems().map { it.value })
            if (!sourceBeforeReturn) { source.move(0, 2); runCurrent() }
            assertEquals(listOf(ids[1], ids[2], ids[0]), history.last().items.softLoadedItems().map { it.stableId })
            val moves = history.flatMap { (it.change as? Change.Mutations)?.operations.orEmpty() }.filterIsInstance<Mutation.Move>()
            assertEquals(if (preview) 2 else 1, moves.size)
            history.assertFlatOracle()
            job.cancelAndJoin()
        }
    }

    @Test fun missedPreviewsAndConfirmationsStillHaveAValidBaseline() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, from, to -> source.move(from, to); true }
        val gate = CompletableDeferred<Unit>()
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { moving.collect {
            history += it
            if (history.size == 1) gate.await()
        } }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2); moving.commitDrag()
        runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems())
        assertIs<Change.Reload>(history.last().change)
        history.assertFlatOracle()
    }

    @Test fun duplicateEqualRowsStillMoveTheirDistinctSynthesizedIds() = runTest {
        data class EqualRow(val label: String)
        val source = mutableDeltaListOf(List(3) { EqualRow("same") })
        val moving = source.moveable { _, from, to -> source.move(from, to); true }
        val history = mutableListOf<Delta<StableItem<EqualRow>>>()
        backgroundScope.launch { moving.withStableIds().collect { history += it } }
        runCurrent()
        val ids = history.last().items.softLoadedItems().map { it.stableId }
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        moving.commitDrag(); runCurrent()
        assertEquals(listOf(ids[1], ids[2], ids[0]), history.last().items.softLoadedItems().map { it.stableId })
        history.assertFlatOracle()
    }

    @Test fun authoritativeReloadResetsIdentityEvenWhenValuesMatchThePreview() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<StableItem<String>>>()
        backgroundScope.launch { moving.withStableIds().collect { history += it } }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        moving.commitDrag()
        val oldIds = history.last().items.softLoadedItems().map { it.stableId }.toSet()
        source.reload(listOf("B", "C", "A")); runCurrent()
        assertIs<Change.Reload>(history.last().change)
        assertTrue(history.last().items.softLoadedItems().none { it.stableId in oldIds })
        history.assertFlatOracle()
    }

    @Test fun cancellationFollowedImmediatelyBySourceInsertCannotLoseRollback() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { moving.collect { history += it } }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        moving.cancelDrag(); source.append("D"); runCurrent()
        assertEquals(listOf("A", "B", "C", "D"), history.last().items.softLoadedItems())
        history.assertFlatOracle()
    }

    @Test fun sourceEditsDuringDragAreRetainedOnCancelOrCommit() = runTest {
        for (commit in listOf(false, true)) {
            val source = mutableDeltaListOf(listOf("A", "B", "C"))
            var moves = 0
            val moving = source.moveable { _, _, _ -> moves++; true }
            val history = mutableListOf<Delta<String>>()
            val job = backgroundScope.launch { moving.collect { history += it } }
            runCurrent()
            moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
            source.append("D"); runCurrent()
            if (commit) assertFalse(moving.commitDrag()) else moving.cancelDrag()
            runCurrent()
            assertEquals(0, moves)
            assertEquals(listOf("A", "B", "C", "D"), history.last().items.softLoadedItems())
            history.assertFlatOracle()
            job.cancelAndJoin()
        }
    }

    @Test fun nativeNoOpAndRejectedTargetsRestoreEarlierPreview() = runTest {
        for (destination in listOf(0, 2)) {
            val source = mutableDeltaListOf(listOf("A", "B", "C"))
            val moving = source.moveable(canMove = { _, _, to -> to != 2 }) { _, _, _ -> true }
            val history = mutableListOf<Delta<String>>()
            val job = backgroundScope.launch { moving.collect { history += it } }
            runCurrent()
            moving.beginDrag(0); moving.updateDragPreview(1); runCurrent()
            assertEquals(destination == 0, moving.commitDrag(destination)); runCurrent()
            assertEquals(listOf("A", "B", "C"), history.last().items.softLoadedItems())
            history.assertFlatOracle()
            job.cancelAndJoin()
        }
    }

    @Test fun failureAfterAuthoritativeConfirmationKeepsThePersistedOrder() = runTest {
        for (failure in 0..2) {
            val source = mutableDeltaListOf(listOf("A", "B", "C"))
            val moving = source.moveable { _, from, to ->
                source.move(from, to)
                yield()
                when (failure) {
                    0 -> false
                    1 -> error("late failure")
                    else -> throw kotlinx.coroutines.CancellationException("late cancellation")
                }
            }
            val history = mutableListOf<Delta<String>>()
            val job = backgroundScope.launch { moving.collect { history += it } }
            runCurrent()
            moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
            if (failure == 2) assertFailsWith<kotlinx.coroutines.CancellationException> { moving.commitDrag() }
            else assertFalse(moving.commitDrag())
            runCurrent()
            assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems())
            assertIs<DragState.Idle>(moving.dragState.value)
            history.assertFlatOracle()
            job.cancelAndJoin()
        }
    }

    @Test fun oldCommitFailureCannotRollbackAResubscribedSourceSnapshot() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val result = CompletableDeferred<Boolean>()
        val moving = source.moveable { _, _, _ -> result.await() }
        var latest: Delta<String>? = null
        val original = backgroundScope.launch { moving.collect { latest = it } }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2)
        val commit = backgroundScope.async { moving.commitDrag() }
        runCurrent()
        original.cancelAndJoin()
        source.append("D")
        val restarted = backgroundScope.launch { moving.collect { latest = it } }
        runCurrent()
        assertEquals(listOf("A", "B", "C", "D"), latest!!.items.softLoadedItems())
        assertFalse(moving.beginDrag(1), "A pending callback still owns the committing state")
        result.complete(false)
        assertFalse(commit.await()); runCurrent()
        assertEquals(listOf("A", "B", "C", "D"), latest!!.items.softLoadedItems())
        assertIs<DragState.Idle>(moving.dragState.value)
        restarted.cancelAndJoin()
    }
}

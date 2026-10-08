@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.assertFlatOracle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DragBackpressureTest {
    @Test fun consecutivePreviewsKeepMovesButSkippedPreviewsReload() = runTest {
        val moving = mutableDeltaListOf(listOf("A", "B", "C")).moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { moving.collect { history += it } }
        runCurrent()
        assertTrue(moving.beginDrag(0))
        moving.updateDragPreview(1); runCurrent()
        assertIs<Change.Mutations>(history.last().change)
        moving.updateDragPreview(0)
        moving.updateDragPreview(2)
        runCurrent()
        assertIs<Change.Reload>(history.last().change)
        assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems())
        history.assertFlatOracle()
    }

    @Test fun suspendedCollectorCanReconstructTheLatestPreview() = runTest {
        val moving = mutableDeltaListOf(listOf(1, 2, 3)).moveable { _, _, _ -> true }
        val gate = CompletableDeferred<Unit>()
        val history = mutableListOf<Delta<Int>>()
        backgroundScope.launch { moving.collect {
            history += it
            if (history.size == 1) gate.await()
        } }
        runCurrent()
        moving.beginDrag(0)
        repeat(150) { moving.updateDragPreview(if (it % 2 == 0) 2 else 0); runCurrent() }
        moving.updateDragPreview(1)
        runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(2, 1, 3), history.last().items.softLoadedItems())
        history.assertFlatOracle()
        assertTrue(history.drop(1).any { it.change is Change.Reload })
    }

    @Test fun collectorJoiningDuringDragStartsWithReload() = runTest {
        val moving = mutableDeltaListOf(listOf(1, 2, 3)).moveable { _, _, _ -> true }
        backgroundScope.launch { moving.collect {} }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        val lateHistory = mutableListOf<Delta<Int>>()
        backgroundScope.launch { moving.collect { lateHistory += it } }
        runCurrent()
        assertEquals(listOf(2, 3, 1), lateHistory.last().items.softLoadedItems())
        lateHistory.assertFlatOracle()
    }
}

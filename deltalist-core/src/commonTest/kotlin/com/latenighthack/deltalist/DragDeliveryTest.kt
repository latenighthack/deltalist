@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DragDeliveryTest {
    @Test fun cancelPublishesTheOriginalOrder() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C"))
        val moving = source.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { moving.collect { history += it } }
        runCurrent()
        assertTrue(moving.beginDrag(0))
        moving.updateDragPreview(2); runCurrent()
        assertEquals(listOf("B", "C", "A"), history.last().items.softLoadedItems())
        moving.cancelDrag(); runCurrent()
        assertEquals(listOf("A", "B", "C"), history.last().items.softLoadedItems())
        assertIs<Change.Reload>(history.last().change)
    }

    @Test fun failedCommitPublishesTheOriginalOrder() = runTest {
        for (throws in listOf(false, true)) {
            val moving = mutableDeltaListOf(listOf(1, 2, 3)).moveable { _, _, _ ->
                if (throws) error("save failed") else false
            }
            var latest: Delta<Int>? = null
            val collection = backgroundScope.launch { moving.collect { latest = it } }
            runCurrent()
            moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
            assertFalse(moving.commitDrag()); runCurrent()
            assertEquals(listOf(1, 2, 3), latest!!.items.softLoadedItems())
            collection.cancelAndJoin()
        }
    }

    @Test fun cancelledCommitPublishesTheOriginalOrder() = runTest {
        val entered = CompletableDeferred<Unit>()
        val moving = mutableDeltaListOf(listOf(1, 2, 3)).moveable { _, _, _ ->
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            true
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { moving.collect { latest = it } }
        runCurrent()
        moving.beginDrag(0); moving.updateDragPreview(2); runCurrent()
        val commit = launch { moving.commitDrag() }
        entered.await(); commit.cancelAndJoin(); runCurrent()
        assertEquals(listOf(1, 2, 3), latest!!.items.softLoadedItems())
        assertIs<DragState.Idle>(moving.dragState.value)
    }
}

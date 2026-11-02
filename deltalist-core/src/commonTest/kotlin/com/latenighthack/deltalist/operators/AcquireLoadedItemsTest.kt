package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.acquireLoadedItems
import com.latenighthack.deltalist.isLoadedAt
import com.latenighthack.deltalist.loadedItems
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AcquireLoadedItemsTest {
    private class Row(val label: String)

    @Test
    fun keepsTheSameObjectsAcrossDeltasUntilAnItemIsUpdated() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val deltas = mutableListOf<Delta<Row>>()
        val job = launch { source.lazyMap { Row(it) }.collect { deltas.add(it) } }
        runCurrent()
        val first = deltas.last().acquireLoadedItems()

        source.append("c")
        runCurrent()
        val afterInsert = deltas.last().acquireLoadedItems()
        assertSame(first[0], afterInsert[0])
        assertSame(first[1], afterInsert[1])

        source.set(0, "a2")
        runCurrent()
        val afterUpdate = deltas.last().acquireLoadedItems()
        assertEquals("a2", afterUpdate[0].label)
        assertNotSame(first[0], afterUpdate[0])
        assertSame(afterInsert[1], afterUpdate[1])
        assertSame(afterInsert[2], afterUpdate[2])
        job.cancel()
    }

    @Test
    fun readingAgainDoesNotPinAnItemTwice() = runTest {
        val source = mutableDeltaListOf(listOf("a"))
        val deltas = MutableSharedFlow<Delta<Row>?>(replay = 1)
        val job = launch { source.lazyMap { Row(it) }.collect { deltas.emit(it) } }
        runCurrent()
        val delta = deltas.filterNotNull().first()

        val once = delta.acquireLoadedItems()
        val twice = delta.acquireLoadedItems()
        assertSame(once[0], twice[0])

        // One release evicts the item, so it was held once, not twice.
        (delta.items as LazyList<Row>).release(0)
        assertNotSame(once[0], delta.acquireLoadedItems()[0])
        job.cancel()
    }

    @Test
    fun peeksBuildAFreshObjectOnEveryRead() = runTest {
        val source = mutableDeltaListOf(listOf("a"))
        val deltas = mutableListOf<Delta<Row>>()
        val job = launch { source.lazyMap { Row(it) }.collect { deltas.add(it) } }
        runCurrent()

        assertNotSame(deltas.last().loadedItems()[0], deltas.last().loadedItems()[0])
        job.cancel()
    }

    @Test
    fun loadStateChecksBuildNothing() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val deltas = mutableListOf<Delta<Row>>()
        var built = 0
        val job = launch { source.lazyMap { built++; Row(it) }.collect { deltas.add(it) } }
        runCurrent()

        assertTrue(deltas.last().isLoadedAt(0))
        assertTrue(deltas.last().isLoadedAt(1))
        assertFalse(deltas.last().isLoadedAt(2))
        assertFalse(deltas.last().isLoadedAt(-1))
        assertEquals(0, built)
        job.cancel()
    }
}

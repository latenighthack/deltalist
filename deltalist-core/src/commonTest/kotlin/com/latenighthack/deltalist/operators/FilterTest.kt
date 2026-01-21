package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterTest {

    @Test
    fun filterInitialReload() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        // Wait for initial emission
        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(2, 4), results[0].items)
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun filterInsertPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 3, 5))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.insert(1, 2) // Insert even number

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        // Second emission should have the inserted item
        assertEquals(listOf(2), results[1].items)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
        assertEquals(1, insert.count)
    }

    @Test
    fun filterInsertNonPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(2, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.insert(1, 3) // Insert odd number - should not appear in filtered

        delay(50)
        job.cancel()

        // Should only have initial reload, no second emission for non-passing insert
        assertEquals(1, results.size)
    }

    @Test
    fun filterRemovePassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.removeAt(1) // Remove 2 (even)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4), results[1].items)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(1, remove.count)
    }

    @Test
    fun filterRemoveNonPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.removeAt(0) // Remove 1 (odd) - not in filtered list

        delay(50)
        job.cancel()

        // Should only have initial reload, no emission for removing non-passing item
        assertEquals(1, results.size)
    }

    @Test
    fun filterUpdateItemStillPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(1, 6) // Change 2 to 6 (still even)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 4), results[1].items)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val update = change.operations[0] as Mutation.Update
        assertEquals(0, update.index)
    }

    @Test
    fun filterUpdateItemNowFails() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(1, 5) // Change 2 to 5 (now odd - should be removed)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4), results[1].items)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
    }

    @Test
    fun filterUpdateItemNowPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(0, 6) // Change 1 to 6 (now even - should be inserted)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 2, 4), results[1].items)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
    }

    @Test
    fun filterBatchInsertMixed() = runTest {
        val source = mutableDeltaListOf(listOf(2, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.update { list ->
            list.add(0, 1) // odd
            list.add(1, 6) // even
            list.add(2, 3) // odd
            list.add(3, 8) // even
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 8, 2, 4), results[1].items)
    }
}

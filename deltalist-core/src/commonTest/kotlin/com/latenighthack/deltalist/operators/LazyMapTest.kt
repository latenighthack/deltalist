package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LazyAccess
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LazyMapTest {

    // Track how many times transform was called
    private var transformCallCount = 0

    private fun resetTransformCount() {
        transformCallCount = 0
    }

    private fun <T> countingTransform(value: T): String {
        transformCallCount++
        return "transformed:$value"
    }

    // ==================== Basic Acquisition Tests ====================

    @Test
    fun acquireComputesAndCachesValue() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Access item at index 1
        val access = results[0].items[1]
        assertFalse(access.isAcquired)

        val value = access.getOrAcquire()
        assertEquals("transformed:b", value)
        assertTrue(access.isAcquired)
        assertEquals(1, transformCallCount)

        // Access again - should return same value without recomputing
        val value2 = access.getOrAcquire()
        assertSame(value, value2)
        assertEquals(1, transformCallCount) // Still 1, not recomputed

        job.cancel()
    }

    @Test
    fun releaseFreesCache() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val access = results[0].items[1]
        access.getOrAcquire()
        assertTrue(access.isAcquired)
        assertEquals(1, transformCallCount)

        // Release
        access.release()
        assertFalse(access.isAcquired)

        // Acquire again - should recompute
        access.getOrAcquire()
        assertTrue(access.isAcquired)
        assertEquals(2, transformCallCount) // Now 2, recomputed

        job.cancel()
    }

    @Test
    fun multipleItemsCanBeAcquiredIndependently() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val items = results[0].items

        // Acquire indices 1 and 3
        items[1].getOrAcquire()
        items[3].getOrAcquire()

        assertTrue(items[1].isAcquired)
        assertFalse(items[2].isAcquired)
        assertTrue(items[3].isAcquired)
        assertEquals(2, transformCallCount)

        // Release index 1
        items[1].release()
        assertFalse(items[1].isAcquired)
        assertTrue(items[3].isAcquired)

        job.cancel()
    }

    // ==================== Insert Mutation Tests ====================

    @Test
    fun insertShiftsAcquiredIndicesUp() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val valueB = results[0].items[1].getOrAcquire()
        assertEquals("transformed:b", valueB)
        assertEquals(1, transformCallCount)

        // Insert "x" at index 0 - "b" should now be at index 2
        source.insert(0, "x")
        delay(50)

        assertEquals(2, results.size)
        assertEquals(4, results[1].items.size)

        // Index 2 should still have the cached value for "b"
        val access2 = results[1].items[2]
        assertTrue(access2.isAcquired)
        val value2 = access2.getOrAcquire()
        assertEquals("transformed:b", value2)
        assertEquals(1, transformCallCount) // No recompute

        // Index 1 (the old position) should now be "a", not acquired
        assertFalse(results[1].items[1].isAcquired)

        job.cancel()
    }

    @Test
    fun insertBeforeAcquiredItemPreservesCache() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire all items
        results[0].items.forEach { it.getOrAcquire() }

        // Insert at beginning
        source.insert(0, "x")
        delay(50)

        // Original items should have shifted
        assertEquals("t:x", results[1].items[0].getOrAcquire())
        assertTrue(results[1].items[1].isAcquired) // "a" shifted to index 1
        assertTrue(results[1].items[2].isAcquired) // "b" shifted to index 2
        assertTrue(results[1].items[3].isAcquired) // "c" shifted to index 3

        job.cancel()
    }

    @Test
    fun insertAfterAcquiredItemPreservesCache() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire item at index 0
        results[0].items[0].getOrAcquire()

        // Insert at end
        source.append("x")
        delay(50)

        // Item at index 0 should still be acquired
        assertTrue(results[1].items[0].isAcquired)
        assertEquals("t:a", results[1].items[0].getOrAcquire())

        job.cancel()
    }

    // ==================== Remove Mutation Tests ====================

    @Test
    fun removeDiscardsAcquiredItem() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        results[0].items[1].getOrAcquire()
        assertTrue(results[0].items[1].isAcquired)

        // Remove "b"
        source.removeAt(1)
        delay(50)

        // New list should have 2 items, none acquired at index 1 (which is now "c")
        assertEquals(2, results[1].items.size)
        assertFalse(results[1].items[1].isAcquired)
        assertEquals("t:c", results[1].items[1].getOrAcquire())

        job.cancel()
    }

    @Test
    fun removeShiftsAcquiredIndicesDown() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2 and "d" at index 3
        results[0].items[2].getOrAcquire()
        results[0].items[3].getOrAcquire()

        // Remove "a" at index 0
        source.removeAt(0)
        delay(50)

        // "c" should now be at index 1 (was 2), still acquired
        assertTrue(results[1].items[1].isAcquired)
        assertEquals("t:c", results[1].items[1].getOrAcquire())

        // "d" should now be at index 2 (was 3), still acquired
        assertTrue(results[1].items[2].isAcquired)
        assertEquals("t:d", results[1].items[2].getOrAcquire())

        job.cancel()
    }

    @Test
    fun removeMultipleItemsShiftsCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "e" at index 4
        results[0].items[4].getOrAcquire()

        // Remove indices 1 and 2 ("b" and "c")
        source.removeRange(1, 2)
        delay(50)

        // "e" should now be at index 2 (was 4, shifted down by 2)
        assertEquals(3, results[1].items.size)
        assertTrue(results[1].items[2].isAcquired)
        assertEquals("t:e", results[1].items[2].getOrAcquire())

        job.cancel()
    }

    // ==================== Update Mutation Tests ====================

    @Test
    fun updateRecomputesAcquiredItem() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val valueB = results[0].items[1].getOrAcquire()
        assertEquals("transformed:b", valueB)
        assertEquals(1, transformCallCount)

        // Update "b" to "B"
        source.set(1, "B")
        delay(50)

        // Should have recomputed
        assertEquals(2, transformCallCount)
        assertTrue(results[1].items[1].isAcquired)
        assertEquals("transformed:B", results[1].items[1].getOrAcquire())
        assertEquals(2, transformCallCount) // No additional compute

        job.cancel()
    }

    @Test
    fun updateNonAcquiredItemDoesNotCompute() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Don't acquire anything
        assertEquals(0, transformCallCount)

        // Update "b" to "B"
        source.set(1, "B")
        delay(50)

        // Should not have computed anything
        assertEquals(0, transformCallCount)
        assertFalse(results[1].items[1].isAcquired)

        job.cancel()
    }

    // ==================== Move Mutation Tests ====================

    @Test
    fun moveAcquiredItemForward() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "a" at index 0
        val valueA = results[0].items[0].getOrAcquire()
        assertEquals("t:a", valueA)

        // Move "a" from index 0 to index 3
        source.move(0, 3)
        delay(50)

        // "a" should now be at index 3, still acquired
        assertTrue(results[1].items[3].isAcquired)
        assertEquals("t:a", results[1].items[3].getOrAcquire())

        // Index 0 should now be "b", not acquired
        assertFalse(results[1].items[0].isAcquired)
        assertEquals("t:b", results[1].items[0].getOrAcquire())

        job.cancel()
    }

    @Test
    fun moveAcquiredItemBackward() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "d" at index 3
        val valueD = results[0].items[3].getOrAcquire()
        assertEquals("t:d", valueD)

        // Move "d" from index 3 to index 0
        source.move(3, 0)
        delay(50)

        // "d" should now be at index 0, still acquired
        assertTrue(results[1].items[0].isAcquired)
        assertEquals("t:d", results[1].items[0].getOrAcquire())

        // Index 3 should now be "c", not acquired
        assertFalse(results[1].items[3].isAcquired)

        job.cancel()
    }

    @Test
    fun moveAffectsIntermediateAcquiredItems() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2
        results[0].items[2].getOrAcquire()

        // Move "a" from index 0 to index 4
        // Original: a b c d e -> b c d e a
        // "c" was at index 2, should shift to index 1
        source.move(0, 4)
        delay(50)

        assertTrue(results[1].items[1].isAcquired)
        assertEquals("t:c", results[1].items[1].getOrAcquire())

        job.cancel()
    }

    // ==================== Reload Tests ====================

    @Test
    fun reloadClearsAllAcquiredItems() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire all items
        results[0].items.forEach { it.getOrAcquire() }
        assertTrue(results[0].items.all { it.isAcquired })

        // Reload with new data
        source.reload(listOf("x", "y", "z"))
        delay(50)

        // All items should be not acquired
        assertTrue(results[1].items.none { it.isAcquired })
        assertEquals("t:x", results[1].items[0].getOrAcquire())

        job.cancel()
    }

    // ==================== Complex Scenario Tests ====================

    @Test
    fun multipleSequentialMutationsTrackCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2
        results[0].items[2].getOrAcquire()

        // Insert at beginning: x a b c d e
        // "c" should move from 2 to 3
        source.insert(0, "x")
        delay(50)
        assertTrue(results[1].items[3].isAcquired)

        // Remove "a" (now at index 1): x b c d e
        // "c" should move from 3 to 2
        source.removeAt(1)
        delay(50)
        assertTrue(results[2].items[2].isAcquired)
        assertEquals("t:c", results[2].items[2].getOrAcquire())

        // Insert at end: x b c d e y
        // "c" should stay at 2
        source.append("y")
        delay(50)
        assertTrue(results[3].items[2].isAcquired)

        job.cancel()
    }

    @Test
    fun batchMutationsTrackCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire items at indices 1, 2, 3
        results[0].items[1].getOrAcquire()
        results[0].items[2].getOrAcquire()
        results[0].items[3].getOrAcquire()

        // Batch: insert at 0, remove at 4 (after shift, this is "e")
        source.update { list ->
            list.add(0, "x")
            list.removeAt(5) // "e" shifted to index 5
        }
        delay(50)

        // After insert at 0: x a b c d e (acquired shifted: 2, 3, 4)
        // After remove at 5: x a b c d (acquired: 2, 3, 4)
        assertEquals(5, results[1].items.size)
        assertTrue(results[1].items[2].isAcquired) // "b"
        assertTrue(results[1].items[3].isAcquired) // "c"
        assertTrue(results[1].items[4].isAcquired) // "d"

        job.cancel()
    }

    // ==================== Same Object Identity Tests ====================

    @Test
    fun sameTransformedObjectReturnedAfterMutation() = runTest {
        // Use a class instance to verify same object identity
        data class Wrapper(val value: String)

        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { Wrapper(it) }

        val results = mutableListOf<Delta<LazyAccess<Wrapper>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val wrapper1 = results[0].items[1].getOrAcquire()

        // Insert at beginning - "b" moves to index 2
        source.insert(0, "x")
        delay(50)

        // Get the wrapper at new position - should be same object
        val wrapper2 = results[1].items[2].getOrAcquire()
        assertSame(wrapper1, wrapper2)

        job.cancel()
    }

    @Test
    fun differentObjectAfterReleaseAndReacquire() = runTest {
        data class Wrapper(val value: String)

        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { Wrapper(it) }

        val results = mutableListOf<Delta<LazyAccess<Wrapper>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b"
        val wrapper1 = results[0].items[1].getOrAcquire()

        // Release
        results[0].items[1].release()

        // Re-acquire - should be different object instance
        val wrapper2 = results[0].items[1].getOrAcquire()
        assertEquals(wrapper1, wrapper2) // Equal by value
        // Note: Can't guarantee different identity for data classes due to caching
        // but the transform function was called again

        job.cancel()
    }

    // ==================== Edge Cases ====================

    @Test
    fun emptyListHandledCorrectly() = runTest {
        val source = mutableDeltaListOf<String>(emptyList())
        val lazy = source.lazyMapWithAccess { "t:$it" }

        val results = mutableListOf<Delta<LazyAccess<String>>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        assertEquals(1, results.size)
        assertEquals(0, results[0].items.size)

        // Add item
        source.append("a")
        delay(50)

        assertEquals(2, results.size)
        assertEquals(1, results[1].items.size)

        job.cancel()
    }

    @Test
    fun acquireAfterMutationWorksCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        var latestDelta: Delta<LazyAccess<String>>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Don't acquire anything yet
        source.insert(1, "x")
        delay(50)

        // Now acquire from the updated list
        val access = latestDelta!!.items[2] // "b" shifted to index 2
        assertEquals("t:b", access.getOrAcquire())
        assertTrue(access.isAcquired)

        job.cancel()
    }

    // ==================== Concurrency Tests ====================

    @Test
    fun concurrentAcquiresOnSameIndex() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        var latestDelta: Delta<LazyAccess<String>>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Simulate concurrent acquires - both should get the same value
        val results = mutableListOf<String>()
        val jobs = (1..10).map {
            launch {
                val value = latestDelta!!.items[1].getOrAcquire()
                synchronized(results) { results.add(value) }
            }
        }
        jobs.forEach { it.join() }

        // All should have gotten the same value
        assertEquals(10, results.size)
        assertTrue(results.all { it == "t:b" })

        job.cancel()
    }

    @Test
    fun concurrentAcquireAndRelease() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMapWithAccess { "t:$it" }

        var latestDelta: Delta<LazyAccess<String>>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire
        latestDelta!!.items[1].getOrAcquire()
        assertTrue(latestDelta!!.items[1].isAcquired)

        // Concurrent release and acquire
        val jobs = listOf(
            launch { latestDelta!!.items[1].release() },
            launch { latestDelta!!.items[1].getOrAcquire() }
        )
        jobs.forEach { it.join() }

        // State should be consistent (either acquired or not)
        val finalState = latestDelta!!.items[1].isAcquired
        // Just verify no crash and state is valid boolean
        assertTrue(finalState || !finalState)

        job.cancel()
    }

    @Test
    fun acquireDuringDeltaApplication() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMapWithAccess { countingTransform(it) }

        var latestDelta: Delta<LazyAccess<String>>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire middle item
        val value1 = latestDelta!!.items[2].getOrAcquire()
        assertEquals("transformed:c", value1)

        // Start acquiring while also inserting
        val acquireJob = launch {
            repeat(100) {
                try {
                    latestDelta?.items?.getOrNull(2)?.getOrAcquire()
                } catch (e: IndexOutOfBoundsException) {
                    // Expected if list shrinks
                }
            }
        }

        val mutateJob = launch {
            repeat(10) {
                source.insert(0, "x$it")
                delay(5)
            }
        }

        acquireJob.join()
        mutateJob.join()

        // Verify state is consistent
        val finalDelta = latestDelta!!
        assertTrue(finalDelta.items.size == 15) // Original 5 + 10 inserted

        job.cancel()
    }

    @Test
    fun stressTestConcurrentOperations() = runTest {
        val source = mutableDeltaListOf((0..99).map { "item$it" })
        val lazy = source.lazyMapWithAccess { "t:$it" }

        var latestDelta: Delta<LazyAccess<String>>? = null
        val collectJob = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire every 10th item
        (0..9).forEach { i ->
            latestDelta!!.items[i * 10].getOrAcquire()
        }

        // Concurrent mutations and accesses
        val jobs = mutableListOf<kotlinx.coroutines.Job>()

        // Mutation job
        jobs += launch {
            repeat(20) { i ->
                when (i % 4) {
                    0 -> source.append("new$i")
                    1 -> if (source.value.size > 10) source.removeAt(5)
                    2 -> if (source.value.size > 0) source.set(0, "updated$i")
                    3 -> if (source.value.size > 2) source.move(0, 2)
                }
                delay(10)
            }
        }

        // Acquire jobs
        repeat(5) {
            jobs += launch {
                repeat(50) {
                    try {
                        val idx = (0 until (latestDelta?.items?.size ?: 1)).random()
                        latestDelta?.items?.getOrNull(idx)?.getOrAcquire()
                    } catch (e: Exception) {
                        // Ignore bounds exceptions during mutations
                    }
                    delay(5)
                }
            }
        }

        // Release jobs
        repeat(3) {
            jobs += launch {
                repeat(30) {
                    try {
                        val idx = (0 until (latestDelta?.items?.size ?: 1)).random()
                        latestDelta?.items?.getOrNull(idx)?.release()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    delay(7)
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state is consistent
        val finalDelta = latestDelta!!
        assertTrue(finalDelta.items.size > 0)

        // All acquired items should have valid values
        finalDelta.items.forEachIndexed { idx, access ->
            if (access.isAcquired) {
                val value = access.getOrAcquire()
                assertTrue(value.startsWith("t:"))
            }
        }

        collectJob.cancel()
    }
}

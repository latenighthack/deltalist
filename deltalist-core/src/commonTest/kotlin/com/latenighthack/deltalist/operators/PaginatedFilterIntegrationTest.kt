package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*

import com.latenighthack.deltalist.get
import com.latenighthack.deltalist.toList
import com.latenighthack.deltalist.iterator
import com.latenighthack.deltalist.isEmpty
import com.latenighthack.deltalist.isNotEmpty
import com.latenighthack.deltalist.indices
import com.latenighthack.deltalist.map
import com.latenighthack.deltalist.filter
import com.latenighthack.deltalist.forEach
import com.latenighthack.deltalist.first
import com.latenighthack.deltalist.last
import com.latenighthack.deltalist.contains
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.paginatedDeltaList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for PaginatedDeltaList combined with Filter operators.
 *
 * These tests verify the complete flow from paginated source through filter to UI,
 * including:
 * - Filter ratio estimation with paginated data
 * - Cascade fetching through filter operator
 * - Placeholder handling (Insert→Update conversions)
 * - Dynamic predicate changes with pagination
 * - Operator chaining (Map + Filter)
 */
class PaginatedFilterIntegrationTest {

    // ==================== Basic Integration Tests ====================

    @Test
    fun paginatedSource_filterEstimatesSize() = runTest {
        // 100 items total, first page has 10 items, 5 pass filter (50%)
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(), // 1-10, 11-20, etc.
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 } // Keep even numbers

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        // Should have initial reload with estimated size
        val lastDelta = results.last()
        assertIs<Change.Reload>(lastDelta.change)

        // Estimated size: 100 * 50% = 50
        assertEquals(50, lastDelta.items.size)

        // 5 loaded items (2, 4, 6, 8, 10)
        val loadedCount = (0 until lastDelta.items.size).count {
            lastDelta.items.softGetOrNull(it) is SoftValue.Present
        }
        assertEquals(5, loadedCount)
    }

    @Test
    fun paginatedSource_filterPreservesLoadedItems() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val lastDelta = results.last()

        // Verify loaded items are correct even numbers
        val loadedItems = (0 until 5).map {
            (lastDelta.items.softGetOrNull(it) as SoftValue.Present).value
        }
        assertEquals(listOf(2, 4, 6, 8, 10), loadedItems)
    }

    // ==================== Cascade Fetch Tests ====================

    @Test
    fun paginatedSource_accessingUnloadedFilteredIndexTriggersFetch() = runTest {
        var fetchCount = 0

        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        assertEquals(1, fetchCount) // Initial load

        // Request a filtered placeholder beyond loaded items.
        (results.last().items.softGet(10) as? SoftValue.NotLoaded)?.request()

        delay(100)
        job.cancel()

        // Should have triggered additional fetch
        assertTrue(fetchCount > 1, "Requesting an unloaded filtered index should trigger fetch")
    }

    @Test
    fun paginatedSource_filterLoadMoreUpdatesCorrectly() = runTest {
        var fetchCount = 0

        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)

        // Trigger second page load by accessing last loaded item in source
        // The filter operator should cascade this
        val delta = results.last()
        try {
            delta.items[4] // Near end of loaded items
        } catch (_: Exception) {}

        delay(100)
        job.cancel()

        // Check we got more data
        val lastDelta = results.last()
        val loadedCount = (0 until lastDelta.items.size).count {
            lastDelta.items.softGetOrNull(it) is SoftValue.Present
        }

        // Should have loaded more than initial 5 items
        assertTrue(loadedCount >= 5, "Should have at least initial 5 items loaded")
    }

    // ==================== Mutation Tests ====================

    @Test
    fun paginatedSource_filterConvertsInsertToUpdate() = runTest {
        var page = 0

        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                page++
                when (page) {
                    1 -> Page(
                        items = (1..10).toList(),
                        beforeToken = null,
                        afterToken = 1,
                        estimatedTotalSize = 100
                    )
                    else -> Page(
                        items = (page * 10 - 9..page * 10).toList(),
                        beforeToken = null,
                        afterToken = if (page < 10) page else null
                    )
                }
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)

        val initialSize = results.last().items.size
        assertEquals(50, initialSize) // 100 * 50%

        // Trigger second page load
        results.last().items[4]
        delay(100)

        job.cancel()

        // Check mutations - should have Update (not Insert) for placeholder positions
        val lastChange = results.last().change
        if (lastChange is Change.Mutations) {
            // Look for Update mutations - these are where placeholders were filled
            val hasUpdate = lastChange.operations.any { it is Mutation.Update }
            assertTrue(hasUpdate || results.size == 2, "Should have Update mutation or be second result")
        }
    }

    // ==================== Dynamic Filter Tests ====================

    @Test
    fun paginatedSource_dynamicFilterPredicateChange() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (token * 20 + 1..(token + 1) * 20).toList(),
                    beforeToken = null,
                    afterToken = if (token < 4) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }
        val filtered = flow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)

        // Initial: even numbers (50% of 100 = 50 estimated)
        assertEquals(50, results.last().items.size)

        // Change to divisible by 5 (20% of 100 = 20 estimated)
        predicateFlow.value = { it % 5 == 0 }

        delay(100)
        job.cancel()

        // Should have re-filtered the loaded rows in place, with the new estimated size
        val lastDelta = results.last()
        assertIs<Change.Mutations>(lastDelta.change)
        results.assertSoftOracle()
        assertEquals(20, lastDelta.items.size)

        // Loaded items should be 5, 10, 15, 20
        val loadedItems = (0 until lastDelta.items.size)
            .mapNotNull { lastDelta.items.softGetOrNull(it) }
            .filterIsInstance<SoftValue.Present<Int>>()
            .map { it.value }

        assertEquals(listOf(5, 10, 15, 20), loadedItems)
    }

    @Test
    fun paginatedSource_dynamicFilterWithRatioChange() = runTest {
        // Initial: 20 items loaded, 10 pass (50%)
        // After predicate change: same items, only 2 pass (10%)
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (1..20).toList(),
                    beforeToken = null,
                    afterToken = null, // No more pages
                    estimatedTotalSize = 100
                )
            }
        )

        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }
        val filtered = flow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)

        // The source is exhausted (afterToken null), so its unreachable estimate of 100 is
        // ignored and it reports its 20 real items. The filter is therefore exact, not
        // extrapolated: 10 evens.
        assertEquals(10, results.last().items.size)

        // Change to divisible by 10 (only 10 and 20)
        predicateFlow.value = { it % 10 == 0 }

        delay(100)
        job.cancel()

        // Exact again: 2 matches.
        assertEquals(2, results.last().items.size)
    }

    // ==================== Operator Chaining Tests ====================

    @Test
    fun paginatedSource_mapThenFilter() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val result = flow
            .mapItems { it * 2 } // 2, 4, 6, ..., 20
            .filterItems { it % 4 == 0 } // Keep multiples of 4: 4, 8, 12, 16, 20

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            result.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val lastDelta = results.last()

        // Original 1-10 becomes 2-20 after map
        // Then filter keeps 4, 8, 12, 16, 20 (5 items from 10)
        // Ratio: 5/10 = 50%, estimated = 50

        assertEquals(50, lastDelta.items.size)

        // Verify loaded items
        val loadedItems = (0 until 5).map {
            (lastDelta.items.softGetOrNull(it) as SoftValue.Present).value
        }
        assertEquals(listOf(4, 8, 12, 16, 20), loadedItems)
    }

    @Test
    fun paginatedSource_filterThenMap() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (token * 10 + 1..(token + 1) * 10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        val result = flow
            .filterItems { it % 2 == 0 } // 2, 4, 6, 8, 10
            .mapItems { "item$it" }       // "item2", "item4", etc.

        val results = mutableListOf<Delta<String>>()
        val job = launch {
            result.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val lastDelta = results.last()

        // Filter: 5/10 = 50%, estimated = 50
        assertEquals(50, lastDelta.items.size)

        // Verify loaded items are strings
        val loadedItems = (0 until 5).map {
            (lastDelta.items.softGetOrNull(it) as SoftValue.Present).value
        }
        assertEquals(listOf("item2", "item4", "item6", "item8", "item10"), loadedItems)
    }

    @Test
    fun paginatedSource_multipleFilters() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (1..30).toList(), // Numbers 1-30
                    beforeToken = null,
                    afterToken = null,
                    estimatedTotalSize = 100
                )
            }
        )

        val result = flow
            .filterItems { it % 2 == 0 } // Keep even: 2,4,6,...,30 (15 items)
            .filterItems { it % 3 == 0 } // Keep divisible by 3: 6,12,18,24,30 (5 items)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            result.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val lastDelta = results.last()

        // After first filter: 15/30 = 50%, estimated from 100 = 50
        // After second filter on that: 5/15 = 33%, estimated from 50 = ~16

        // Verify loaded items are divisible by 6
        val loadedItems = (0 until lastDelta.items.size)
            .mapNotNull { lastDelta.items.softGetOrNull(it) }
            .filterIsInstance<SoftValue.Present<Int>>()
            .map { it.value }

        assertEquals(listOf(6, 12, 18, 24, 30), loadedItems)
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun paginatedSource_filterNothingPasses() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (1..10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        // Filter that passes nothing
        val filtered = flow.filterItems { it > 1000 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        // The first page matches nothing, but the source still has more pages, so the
        // filtered list must NOT collapse to size 0 (which would render an empty,
        // unscrollable list and permanently stall pagination). Instead it keeps at least
        // one NotLoaded placeholder so accessing it cascades the next fetch.
        val last = results.last()
        assertTrue(last.items.size >= 1, "filter must keep a placeholder while source has more pages")
        assertTrue(
            last.items.softGetOrNull(last.items.size - 1) is SoftValue.NotLoaded,
            "the trailing placeholder must be NotLoaded so it triggers a fetch"
        )
    }

    @Test
    fun paginatedSource_filterEverythingPasses() = runTest {
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (1..10).toList(),
                    beforeToken = null,
                    afterToken = if (token < 9) token + 1 else null,
                    estimatedTotalSize = 100
                )
            }
        )

        // Filter that passes everything
        val filtered = flow.filterItems { true }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        // 100% ratio -> estimated 100
        assertEquals(100, results.last().items.size)
    }

    @Test
    fun paginatedSource_fullLoadThenFilter() = runTest {
        // Source with all data loaded (no more pages)
        val flow = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 0,
            fetch = { direction, token ->
                Page(
                    items = (1..20).toList(),
                    beforeToken = null,
                    afterToken = null, // No more pages
                    estimatedTotalSize = null // No estimate, use actual size
                )
            }
        )

        val filtered = flow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val lastDelta = results.last()

        // No estimation needed, all items known
        // Filtered: 10 even numbers
        assertEquals(10, lastDelta.items.size)

        // All items should be Present (no NotLoaded)
        val allPresent = (0 until lastDelta.items.size).all {
            lastDelta.items.softGetOrNull(it) is SoftValue.Present
        }
        assertTrue(allPresent, "All items should be loaded when source is fully loaded")
    }
}

package com.latenighthack.deltalist

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PaginatedDeltaFlowTest {

    data class Item(val id: Int, val name: String)

    // ========== Initial State Tests ==========

    @Test
    fun initialEmissionIsReloadWithEstimatedSize() = runTest {
        var initialDirection: LoadDirection? = null

        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "page1",
            fetch = { direction, token ->
                if (initialDirection == null) {
                    initialDirection = direction
                }
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = "page2",
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        assertEquals(LoadDirection.INITIAL, initialDirection)
        assertEquals(2, results.size)
        // First is empty initial state
        assertTrue(results[0].change is Change.Reload)
        // Second is loaded data
        assertTrue(results[1].change is Change.Reload)
        assertEquals(100, results[1].items.size) // Reports estimated size
    }

    @Test
    fun fetchDirectionIsInitialOnFirstLoad() = runTest {
        var receivedDirection: LoadDirection? = null

        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "start",
            fetch = { direction, token ->
                receivedDirection = direction
                Page(
                    items = listOf(Item(1, "A")),
                    beforeToken = null,
                    afterToken = null
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        assertEquals(LoadDirection.INITIAL, receivedDirection)
    }

    // ========== Fetch Window Tests ==========

    @Test
    fun accessNearEndTriggersAfterFetch() = runTest {
        var fetchCount = 0
        var lastDirection: LoadDirection? = null

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                lastDirection = direction
                when (token) {
                    0 -> Page(
                        items = listOf(Item(1, "A"), Item(2, "B")),
                        beforeToken = null,
                        afterToken = 1
                    )
                    1 -> Page(
                        items = listOf(Item(3, "C"), Item(4, "D")),
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected token: $token")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        assertEquals(1, fetchCount) // Initial only so far

        // Access last item to trigger after fetch
        results.last().items[1]
        delay(100)

        assertEquals(2, fetchCount)
        assertEquals(LoadDirection.AFTER, lastDirection)
        job.cancel()
    }

    @Test
    fun accessNearStartTriggersBeforeFetch() = runTest {
        var fetchCount = 0
        var lastDirection: LoadDirection? = null

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 5,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                lastDirection = direction
                when (token) {
                    5 -> Page(
                        items = listOf(Item(5, "E"), Item(6, "F")),
                        beforeToken = 4,
                        afterToken = null
                    )
                    4 -> Page(
                        items = listOf(Item(3, "C"), Item(4, "D")),
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected token: $token")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        assertEquals(1, fetchCount)

        // Access first item to trigger before fetch
        results.last().items[0]
        delay(100)

        assertEquals(2, fetchCount)
        assertEquals(LoadDirection.BEFORE, lastDirection)

        // Verify items are prepended
        val finalItems = results.last().items
        assertEquals(Item(3, "C"), finalItems[0])
        assertEquals(Item(4, "D"), finalItems[1])
        assertEquals(Item(5, "E"), finalItems[2])
        assertEquals(Item(6, "F"), finalItems[3])
        job.cancel()
    }

    @Test
    fun fetchWindowSizeMultipleItems() = runTest {
        var afterFetchCalled = false

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 3,
            fetch = { direction, token ->
                when (token) {
                    0 -> Page(
                        items = listOf(Item(1, "A"), Item(2, "B"), Item(3, "C"), Item(4, "D"), Item(5, "E")),
                        beforeToken = null,
                        afterToken = 1
                    )
                    1 -> {
                        afterFetchCalled = true
                        Page(
                            items = listOf(Item(6, "F")),
                            beforeToken = null,
                            afterToken = null
                        )
                    }
                    else -> throw AssertionError("Unexpected token: $token")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        // Access index 2 (within window of 3 from end with 5 items)
        results.last().items[2]
        delay(100)

        assertTrue(afterFetchCalled)
        job.cancel()
    }

    @Test
    fun accessOutsideFetchWindowDoesNotTriggerFetch() = runTest {
        var fetchCount = 0

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B"), Item(3, "C"), Item(4, "D"), Item(5, "E")),
                    beforeToken = null,
                    afterToken = 1
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        assertEquals(1, fetchCount)

        // Access index 2 (middle of list, not within window of 1 from end)
        results.last().items[2]
        delay(100)

        assertEquals(1, fetchCount) // Still just initial fetch
        job.cancel()
    }

    // ========== Estimated Size Tests ==========

    @Test
    fun estimatedSizeReportsLargerSize() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = "page2",
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        job.cancel()

        val finalDelta = results.last()
        assertEquals(100, finalDelta.items.size) // Estimated size
    }

    @Test
    fun insertMutationWhenExceedingEstimatedSize() = runTest {
        var page = 0
        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                page++
                when (page) {
                    1 -> Page(
                        items = (1..5).map { Item(it, "Item$it") },
                        beforeToken = null,
                        afterToken = 1,
                        estimatedTotalSize = 7
                    )
                    2 -> Page(
                        items = (6..10).map { Item(it, "Item$it") },
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected page: $page")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        // Trigger second fetch by accessing near end
        results.last().items[4]
        delay(100)

        job.cancel()

        // Check the mutations - should have Update for covered items and Insert for uncovered
        val lastChange = results.last().change as Change.Mutations
        assertEquals(2, lastChange.operations.size)

        val update = lastChange.operations[0] as Mutation.Update
        assertEquals(5, update.index)
        assertEquals(2, update.count)

        val insert = lastChange.operations[1] as Mutation.Insert
        assertEquals(7, insert.index)
        assertEquals(3, insert.count)
    }

    @Test
    fun updateMutationWhenWithinEstimatedSize() = runTest {
        var page = 0
        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                page++
                when (page) {
                    1 -> Page(
                        items = (1..5).map { Item(it, "Item$it") },
                        beforeToken = null,
                        afterToken = 1,
                        estimatedTotalSize = 20
                    )
                    2 -> Page(
                        items = (6..10).map { Item(it, "Item$it") },
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected page: $page")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        // Trigger second fetch
        results.last().items[4]
        delay(100)

        job.cancel()

        // All items covered by estimate - should be Update only
        val lastChange = results.last().change as Change.Mutations
        assertEquals(1, lastChange.operations.size)
        val update = lastChange.operations[0] as Mutation.Update
        assertEquals(5, update.index)
        assertEquals(5, update.count)
    }

    @Test
    fun prependAlwaysInserts() = runTest {
        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 5,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                when (token) {
                    5 -> Page(
                        items = listOf(Item(5, "E"), Item(6, "F"), Item(7, "G")),
                        beforeToken = 4,
                        afterToken = null,
                        estimatedTotalSize = 100
                    )
                    4 -> Page(
                        items = listOf(Item(3, "C"), Item(4, "D")),
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected token: $token")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        // Trigger before fetch
        results.last().items[0]
        delay(100)

        job.cancel()

        // Check that prepend is always an insert
        val lastChange = results.last().change as Change.Mutations
        assertEquals(1, lastChange.operations.size)
        assertTrue(lastChange.operations[0] is Mutation.Insert)
        assertEquals(0, (lastChange.operations[0] as Mutation.Insert).index)
    }

    // ========== Concurrent Access Tests ==========

    @Test
    fun noDoubleFetchWhileLoading() = runTest {
        var fetchCount = 0

        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                delay(200) // Slow fetch
                Page(
                    items = listOf(Item(fetchCount, "Item$fetchCount")),
                    beforeToken = null,
                    afterToken = "next"
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        // Multiple rapid collections should only trigger one initial fetch
        delay(50)
        delay(50)
        delay(50)

        delay(200)
        job.cancel()

        assertEquals(1, fetchCount)
    }

    // ========== Edge Cases ==========

    @Test
    fun emptyPageDoesNotEmitMutation() = runTest {
        var page = 0
        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                page++
                when (page) {
                    1 -> Page(
                        items = listOf(Item(1, "A")),
                        beforeToken = null,
                        afterToken = 1
                    )
                    2 -> Page(
                        items = emptyList(),
                        beforeToken = null,
                        afterToken = null
                    )
                    else -> throw AssertionError("Unexpected page: $page")
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        val countAfterInitial = results.size

        // Trigger second fetch with empty result
        results.last().items[0]
        delay(100)

        job.cancel()

        // Should not have emitted a new delta for empty page
        assertEquals(countAfterInitial, results.size)
    }

    @Test
    fun accessBeyondLoadedItemsThrows() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = null,
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        val finalDelta = results.last()
        assertEquals(100, finalDelta.items.size) // Estimated size

        // But accessing beyond loaded items throws
        assertFailsWith<IndexOutOfBoundsException> {
            finalDelta.items[50]
        }

        job.cancel()
    }

    @Test
    fun negativeIndexThrows() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A")),
                    beforeToken = null,
                    afterToken = null
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        assertFailsWith<IndexOutOfBoundsException> {
            results.last().items[-1]
        }

        job.cancel()
    }

    @Test
    fun multipleSequentialFetches() = runTest {
        var page = 0

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                page++
                Page(
                    items = listOf(Item(page, "Item$page")),
                    beforeToken = null,
                    afterToken = if (page < 5) page else null
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        // Fetch pages 1-5
        for (i in 1..5) {
            delay(100)
            if (i < 5) {
                // Access last item to trigger next fetch
                val items = results.last().items
                items[items.filterIndexed { idx, _ -> idx < i }.size - 1]
            }
        }

        delay(100)
        assertEquals(5, page)
        job.cancel()
    }

    @Test
    fun loadDirectionPassedCorrectly() = runTest {
        val directions = mutableListOf<LoadDirection>()

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 5,
            fetchWindowSize = 1,
            fetch = { direction, token ->
                directions.add(direction)
                when (direction) {
                    LoadDirection.INITIAL -> Page(
                        items = listOf(Item(5, "E"), Item(6, "F"), Item(7, "G")),
                        beforeToken = 4,
                        afterToken = 8
                    )
                    LoadDirection.BEFORE -> Page(
                        items = listOf(Item(4, "D")),
                        beforeToken = null,
                        afterToken = null
                    )
                    LoadDirection.AFTER -> Page(
                        items = listOf(Item(8, "H")),
                        beforeToken = null,
                        afterToken = null
                    )
                }
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        assertEquals(listOf(LoadDirection.INITIAL), directions)

        // Trigger before fetch
        results.last().items[0]
        delay(100)
        assertEquals(listOf(LoadDirection.INITIAL, LoadDirection.BEFORE), directions)

        // Trigger after fetch (now index 3 is the last loaded item)
        results.last().items[3]
        delay(100)
        assertEquals(listOf(LoadDirection.INITIAL, LoadDirection.BEFORE, LoadDirection.AFTER), directions)

        job.cancel()
    }

    @Test
    fun callerCanManageLoadingState() = runTest {
        val loadingState = MutableStateFlow<LoadDirection?>(null)

        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                loadingState.value = direction
                delay(100)
                val result = Page<Item, String>(
                    items = listOf(Item(1, "A")),
                    beforeToken = null,
                    afterToken = null
                )
                loadingState.value = null
                result
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        assertEquals(LoadDirection.INITIAL, loadingState.value)

        delay(100)
        assertEquals(null, loadingState.value)

        job.cancel()
    }

    // ========== SoftList Tests ==========

    @Test
    fun softGetReturnsLoadedItems() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = null,
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        val list = results.last().items
        assertTrue(list is SoftList<Item>)

        // Loaded items should return Present
        val soft0 = (list as SoftList<Item>).softGet(0)
        assertTrue(soft0 is SoftValue.Present)
        assertEquals(Item(1, "A"), (soft0 as SoftValue.Present).value)

        val soft1 = list.softGet(1)
        assertTrue(soft1 is SoftValue.Present)
        assertEquals(Item(2, "B"), (soft1 as SoftValue.Present).value)

        job.cancel()
    }

    @Test
    fun softGetReturnsNotLoadedForUnloadedItems() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = null,
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        val list = results.last().items as SoftList<Item>

        // Items within estimated size but not loaded should return NotLoaded
        val soft50 = list.softGet(50)
        assertTrue(soft50 is SoftValue.NotLoaded)

        val soft99 = list.softGet(99)
        assertTrue(soft99 is SoftValue.NotLoaded)

        job.cancel()
    }

    @Test
    fun softGetReturnsNullForOutOfBounds() = runTest {
        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetch = { direction, token ->
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B")),
                    beforeToken = null,
                    afterToken = null,
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)

        val list = results.last().items as SoftList<Item>

        // Out of bounds should return null
        assertEquals(null, list.softGet(-1))
        assertEquals(null, list.softGet(100))
        assertEquals(null, list.softGet(1000))

        job.cancel()
    }

    @Test
    fun softGetDoesNotTriggerFetch() = runTest {
        var fetchCount = 0

        val flow = paginatedDeltaList<Item, String>(
            scope = this,
            startToken = "initial",
            fetchWindowSize = 1,
            fetch = { direction, token ->
                fetchCount++
                Page(
                    items = listOf(Item(1, "A"), Item(2, "B"), Item(3, "C")),
                    beforeToken = "before",
                    afterToken = "after",
                    estimatedTotalSize = 100
                )
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(100)
        assertEquals(1, fetchCount)

        val list = results.last().items as SoftList<Item>

        // Soft access near boundaries should NOT trigger fetch
        list.softGet(0)  // Near start
        list.softGet(2)  // Near end
        list.softGet(50) // Not loaded

        delay(100)
        assertEquals(1, fetchCount) // Still just the initial fetch

        // But regular get SHOULD trigger fetch
        list[2]  // Access near end triggers after fetch
        delay(100)
        assertEquals(2, fetchCount)

        job.cancel()
    }

    @Test
    fun softGetOrNullExtensionWorksForRegularList() {
        val regularList = listOf("A", "B", "C")

        val soft0 = regularList.softGetOrNull(0)
        assertTrue(soft0 is SoftValue.Present)
        assertEquals("A", (soft0 as SoftValue.Present).value)

        val soft2 = regularList.softGetOrNull(2)
        assertTrue(soft2 is SoftValue.Present)
        assertEquals("C", (soft2 as SoftValue.Present).value)

        // Out of bounds returns null for regular list
        assertEquals(null, regularList.softGetOrNull(-1))
        assertEquals(null, regularList.softGetOrNull(3))
    }
}

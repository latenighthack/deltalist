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
import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for filterItemsDynamic operator which supports changing predicates.
 */
class FilterDynamicTest {

    @Test
    fun filterDynamic_initialEmissionAfterPredicateSet() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(2, 4), results[0].items.toList())
        assertIs<Change.Reload>(results[0].change)
    }

    @Test
    fun filterDynamic_predicateChangeEmitsMutations() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4, 6), results.last().items.toList())

        // Change predicate to odd numbers
        predicateFlow.value = { it % 2 == 1 }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(1, 3, 5), results[1].items.toList())
        assertIs<Change.Mutations>(results[1].change)
        results.assertFlatOracle()
    }

    @Test
    fun filterDynamic_predicateChangeToMatchNothing() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4), results.last().items.toList())

        // Change predicate to match nothing
        predicateFlow.value = { it > 100 }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(emptyList(), results[1].items.toList())
        assertIs<Change.Mutations>(results[1].change)
        results.assertFlatOracle()
    }

    @Test
    fun filterDynamic_predicateChangeToMatchEverything() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4), results.last().items.toList())

        // Change predicate to match everything
        predicateFlow.value = { true }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(1, 2, 3, 4, 5), results[1].items.toList())
        assertIs<Change.Mutations>(results[1].change)
        results.assertFlatOracle()
    }

    @Test
    fun filterDynamic_sourceInsertAfterPredicateSet() = runTest {
        // Start with a list that has at least one passing item so mutations work
        val source = mutableDeltaListOf(listOf(2, 3, 5))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2), results.last().items.toList())

        // Insert another even number
        source.insert(2, 4)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(2, 4), results[1].items.toList())
        val change = results[1].change
        assertIs<Change.Mutations>(change)
        assertEquals(1, change.operations.size)
        assertIs<Mutation.Insert>(change.operations[0])
    }

    @Test
    fun filterDynamic_sourceUpdateAfterPredicateSet() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4), results.last().items.toList())

        // Update 2 to 6 (still passes)
        source.set(1, 6)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 4), results[1].items.toList())
        val change = results[1].change
        assertIs<Change.Mutations>(change)
        assertIs<Mutation.Update>(change.operations[0])
    }

    @Test
    fun filterDynamic_sourceRemoveAfterPredicateSet() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4), results.last().items.toList())

        // Remove 2
        source.removeAt(1)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4), results[1].items.toList())
        val change = results[1].change
        assertIs<Change.Mutations>(change)
        assertIs<Mutation.Remove>(change.operations[0])
    }

    @Test
    fun filterDynamic_deltaArrivesBeforePredicate() = runTest {
        // Create source flow that emits immediately
        val sourceFlow = MutableStateFlow(Delta(listOf(1, 2, 3, 4, 5), Change.Reload))

        // Predicate flow that doesn't emit immediately
        val predicateFlow = MutableStateFlow<(Int) -> Boolean>({ false }) // Placeholder

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Now set the real predicate - should process the pending delta
        predicateFlow.value = { it % 2 == 0 }

        delay(50)
        job.cancel()

        // Should have received the filtered result
        assertTrue(results.isNotEmpty())
        assertEquals(listOf(2, 4), results.last().items.toList())
    }

    @Test
    fun filterDynamic_multipleRapidPredicateChanges() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Rapid changes
        predicateFlow.value = { it % 3 == 0 }
        predicateFlow.value = { it % 5 == 0 }
        predicateFlow.value = { it > 5 }

        delay(100)
        job.cancel()

        // Final state should reflect last predicate
        assertEquals(listOf(6, 7, 8, 9, 10), results.last().items.toList())
    }

    @Test
    fun filterDynamic_emptySourceWithPredicateChange() = runTest {
        val source = mutableDeltaListOf<Int>(emptyList())
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = source.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(emptyList(), results.last().items.toList())

        // Change predicate on empty source
        predicateFlow.value = { it % 3 == 0 }

        delay(50)
        job.cancel()

        // Should handle gracefully - no new emission for empty list predicate change
        // (or emit another reload with empty list)
        assertTrue(results.all { it.items.isEmpty() })
    }

    // Tests with paginated sources

    /**
     * Mock paginated list for testing
     */
    private class MockPaginatedList<T>(
        private val loadedItems: List<T>,
        private val estimatedTotal: Int,
        private val onFetchTriggered: () -> Unit = {}
    ) : AbstractSoftList<T>() {

        override val size: Int = maxOf(loadedItems.size, estimatedTotal)

        override fun softGet(index: Int): SoftValue<T>? {
            if (index < 0 || index >= size) return null
            return if (index < loadedItems.size) {
                SoftValue.Present(loadedItems[index])
            } else {
                SoftValue.NotLoaded { onFetchTriggered() }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is MockPaginatedList<*>) return false
            return loadedItems == other.loadedItems && estimatedTotal == other.estimatedTotal
        }

        override fun hashCode(): Int {
            var result = loadedItems.hashCode()
            result = 31 * result + estimatedTotal
            return result
        }
    }

    @Test
    fun filterDynamic_paginatedSource_predicateChange() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Initial: 5 even numbers, 50% ratio, estimated 50
        assertEquals(50, results.last().items.size)

        // Change predicate to divisible by 5
        predicateFlow.value = { it % 5 == 0 }

        delay(50)
        job.cancel()

        // New: 2 items (5, 10), 20% ratio, estimated 20
        assertEquals(20, results.last().items.size)
        assertIs<Change.Mutations>(results.last().change)
        results.assertSoftOracle()
    }

    @Test
    fun filterDynamic_paginatedSource_clearsAccessIndicesOnPredicateChange() = runTest {
        var fetchCount = 0
        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 50,
                    onFetchTriggered = { fetchCount++ }
                ),
                Change.Reload
            )
        )
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Access an unloaded position to add it to pending
        val lastDelta = results.last()
        try {
            lastDelta.items[20] // This should trigger fetch tracking
        } catch (_: IndexOutOfBoundsException) {}

        // Change predicate - should clear pending access indices
        predicateFlow.value = { it % 3 == 0 }

        delay(50)
        job.cancel()

        // The predicate change re-filtered the loaded rows in place, clearing pending accesses
        assertIs<Change.Mutations>(results.last().change)
        results.assertSoftOracle()
    }

    @Test
    fun filterDynamic_predicateChangeKeepsRetainedRowsInPlace() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6))
        val excluded = MutableStateFlow(setOf(2, 5))
        val filtered = source.filterItemsDynamic(excluded.map { keys -> { item: Int -> item !in keys } })

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }

        delay(50)
        assertEquals(listOf(1, 3, 4, 6), results.last().items.toList())

        // 2 comes back and 4 leaves: one insert and one remove, never a reload.
        excluded.value = setOf(4, 5)
        delay(50)
        assertEquals(listOf(1, 2, 3, 6), results.last().items.toList())
        assertEquals(
            Change.Mutations(listOf(Mutation.Insert(1, 1), Mutation.Remove(3, 1))),
            results.last().change
        )

        // Everything leaves, then comes back.
        excluded.value = setOf(1, 2, 3, 4, 5, 6)
        delay(50)
        assertEquals(emptyList(), results.last().items.toList())
        excluded.value = emptySet()
        delay(50)
        job.cancel()

        assertEquals(listOf(1, 2, 3, 4, 5, 6), results.last().items.toList())
        assertEquals(Change.Mutations(listOf(Mutation.Insert(0, 6))), results.last().change)
        results.assertFlatOracle()
    }

    @Test
    fun filterDynamic_predicateChangeWithNoRowChangeEmitsNothing() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3))
        val excluded = MutableStateFlow(setOf(2))
        val filtered = source.filterItemsDynamic(excluded.map { keys -> { item: Int -> item !in keys } })

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }

        delay(50)
        // 9 isn't in the source, so no row leaves or joins.
        excluded.value = setOf(2, 9)
        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(1, 3), results.last().items.toList())
    }

    @Test
    fun filterDynamic_predicateChangeOverPagedSourceKeepsTrailingSlot() = runTest {
        val source = paginatedDeltaList<Int, Int>(
            scope = backgroundScope,
            startToken = 0,
            fetch = { _, token ->
                Page(
                    items = (token * 5 + 1..(token + 1) * 5).toList(),
                    beforeToken = null,
                    afterToken = if (token < 3) token + 1 else null,
                )
            }
        )
        val excluded = MutableStateFlow(setOf(1, 2))
        val filtered = source.filterItemsDynamic(excluded.map { keys -> { item: Int -> item !in keys } })

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }

        delay(50)
        assertEquals(listOf(3, 4, 5), results.last().items.softLoadedItems())
        assertIs<SoftValue.NotLoaded>(results.last().items.softGet(results.last().items.size - 1))

        excluded.value = setOf(4)
        delay(50)
        job.cancel()

        val last = results.last()
        assertIs<Change.Mutations>(last.change)
        assertEquals(listOf(1, 2, 3, 5), last.items.softLoadedItems())
        assertIs<SoftValue.NotLoaded>(last.items.softGet(last.items.size - 1))
        results.assertSoftOracle()
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.getItemCountAt
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [ifEmpty] — the placeholder-when-empty operator that makes empty states real list
 * items instead of overlay views.
 */
class IfEmptyTest {

    private val placeholder = "«empty»"

    /** Paginated-style snapshot: an unloaded tail past the loaded prefix. */
    private class MockSoftList<T>(
        private val loadedItems: List<T>,
        private val estimatedTotal: Int
    ) : AbstractSoftList<T>() {
        var softGetCount: Int = 0
            private set

        override val size: Int = estimatedTotal

        override fun softGet(index: Int): SoftValue<T>? {
            softGetCount++
            if (index < 0 || index >= size) return null
            return if (index < loadedItems.size) SoftValue.Present(loadedItems[index]) else SoftValue.NotLoaded()
        }
    }

    private fun delta(vararg items: String, change: Change = Change.Reload) =
        Delta(items.toList(), change)

    // ==================== Transitions ====================

    @Test
    fun firstEmissionEmptyCarriesPlaceholderAsReload() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }) {}

        assertEquals(1, deltas.size)
        assertIs<Change.Reload>(deltas[0].change)
        assertEquals(listOf(placeholder), deltas[0].items.softLoadedItems())
    }

    @Test
    fun firstEmissionNonEmptyPassesThroughAsReload() = runTest {
        val source = MutableStateFlow(delta("a", "b"))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {}

        assertEquals(1, deltas.size)
        assertIs<Change.Reload>(deltas[0].change)
        assertEquals(listOf("a", "b"), deltas[0].items.softLoadedItems())
    }

    @Test
    fun nonEmptyToEmptyIsReloadWithPlaceholder() = runTest {
        val source = MutableStateFlow(delta("a"))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, delta(change = Change.Mutations(Mutation.Remove(0, 1))))
        }

        assertEquals(2, deltas.size)
        assertIs<Change.Reload>(deltas[1].change)
        assertEquals(listOf(placeholder), deltas[1].items.softLoadedItems())
        deltas.assertFlatOracle()
    }

    @Test
    fun emptyToNonEmptyIsReloadWithUpstreamItems() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, delta("a", "b", change = Change.Mutations(Mutation.Insert(0, 2))))
        }

        assertEquals(2, deltas.size)
        assertIs<Change.Reload>(deltas[1].change)
        assertEquals(listOf("a", "b"), deltas[1].items.softLoadedItems())
        deltas.assertFlatOracle()
    }

    @Test
    fun emptyToEmptyEmitsNothing() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            // A cold repository re-hydrating while still empty must not re-emit: downstream
            // concat/sectionedDeltaList would upgrade a no-op change to a full Reload.
            step(source, delta(change = Change.Reload))
            step(source, delta(change = Change.Mutations(emptyList())))
        }

        assertEquals(1, deltas.size)
    }

    @Test
    fun nonEmptyToNonEmptyPassesUpstreamChangeThrough() = runTest {
        val source = MutableStateFlow(delta("a"))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, delta("a", "b", change = Change.Mutations(Mutation.Insert(1, 1))))
        }

        assertEquals(2, deltas.size)
        assertEquals(Change.Mutations(Mutation.Insert(1, 1)), deltas[1].change)
        deltas.assertFlatOracle()
    }

    @Test
    fun mutationDrivenTransitionToEmptyBecomesReload() = runTest {
        val source = MutableStateFlow(delta("a", "b"))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, delta(change = Change.Mutations(Mutation.Remove(0, 2))))
        }

        // The upstream Remove would leave 0 rows; we carry 1, so the remove must not pass through.
        assertIs<Change.Reload>(deltas[1].change)
        deltas.assertFlatOracle()
    }

    @Test
    fun emptyNonEmptyEmptyRoundTripIsSelfConsistent() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, delta("a", change = Change.Mutations(Mutation.Insert(0, 1))))
            step(source, delta("a", "b", change = Change.Mutations(Mutation.Insert(1, 1))))
            step(source, delta(change = Change.Mutations(Mutation.Remove(0, 2))))
            step(source, delta("c", change = Change.Mutations(Mutation.Insert(0, 1))))
        }

        assertEquals(5, deltas.size)
        assertEquals(
            listOf(
                listOf(placeholder),
                listOf("a"),
                listOf("a", "b"),
                listOf(placeholder),
                listOf("c")
            ),
            deltas.map { it.items.softLoadedItems() }
        )
        deltas.assertFlatOracle()
    }

    // ==================== Placeholder identity ====================

    @Test
    fun placeholderFactoryInvokedAtMostOncePerCollection() = runTest {
        var built = 0
        val source = MutableStateFlow(Delta(emptyList<Any>(), Change.Reload))

        val deltas = collectDriven(source.ifEmpty { built++; Any() }) {
            step(source, Delta(listOf(Any()), Change.Mutations(Mutation.Insert(0, 1))))
            step(source, Delta(emptyList<Any>(), Change.Mutations(Mutation.Remove(0, 1))))
        }

        assertEquals(1, built)
        // Both empty emissions carry the very same instance, so binders keyed on item identity
        // (and lazyMap's cache) see one stable row rather than a churning one.
        assertSame(deltas[0].items.softLoadedItems()[0], deltas[2].items.softLoadedItems()[0])
    }

    @Test
    fun separateCollectionsGetSeparatePlaceholders() = runTest {
        val source = MutableStateFlow(Delta(emptyList<Any>(), Change.Reload))
        val flow = source.ifEmpty { Any() }

        val first = collectDriven(flow) {}
        val second = collectDriven(flow) {}

        assertTrue(first[0].items.softLoadedItems()[0] !== second[0].items.softLoadedItems()[0])
    }

    @Test
    fun placeholderNotConstructedWhenListNeverEmpty() = runTest {
        var built = 0
        val source = MutableStateFlow(delta("a"))

        collectDriven(source.ifEmpty { built++; placeholder }) {
            step(source, delta("a", "b", change = Change.Mutations(Mutation.Insert(1, 1))))
        }

        assertEquals(0, built)
    }

    // ==================== Soft-list correctness ====================

    @Test
    fun paginatedListWithUnloadedTailIsNotEmpty() = runTest {
        // Nothing loaded yet, but 10 slots are expected: that is a loading list, not an empty one.
        val source = MutableStateFlow(Delta(MockSoftList(emptyList<String>(), 10), Change.Reload))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {}

        assertEquals(10, deltas[0].items.size)
        assertEquals(emptyList(), deltas[0].items.softLoadedItems())
    }

    @Test
    fun neverCallsSoftGetOnUpstream() = runTest {
        val loading = MockSoftList(listOf("a"), 5)
        val source = MutableStateFlow(Delta(loading, Change.Reload))

        collectDriven(source.ifEmpty { placeholder }) {
            step(source, Delta(loading, Change.Mutations(Mutation.Update(0, 1))))
        }

        // Forcing a load here would defeat pagination and, for a LazyList, materialize every row.
        assertEquals(0, loading.softGetCount)
    }

    @Test
    fun emptySnapshotSoftGetContract() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }) {}
        val items = deltas[0].items

        assertEquals(1, items.size)
        assertEquals(SoftValue.Present(placeholder), items.softGet(0))
        assertNull(items.softGet(1))
        assertNull(items.softGet(-1))
    }

    @Test
    fun softOracleHoldsAcrossPaginatedAndEmptySnapshots() = runTest {
        val source = MutableStateFlow(Delta(MockSoftList(listOf("a"), 4), Change.Reload))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            step(source, Delta(MockSoftList(emptyList<String>(), 0), Change.Reload))
            step(source, Delta(MockSoftList(listOf("a", "b"), 4), Change.Reload))
        }

        deltas.assertSoftOracle()
    }

    // ==================== Interaction with other operators ====================

    @Test
    fun appliedAfterLazyMapPreservesLazyListOnNonEmptySnapshots() = runTest {
        val source = MutableStateFlow(delta("a"))

        val deltas = collectDriven(
            source.lazyMap<String, String> { it.uppercase() }.ifEmpty { placeholder }
        ) {
            step(source, delta(change = Change.Mutations(Mutation.Remove(0, 1))))
        }

        assertIs<LazyList<String>>(deltas[0].items)
        assertEquals(listOf("A"), deltas[0].items.softLoadedItems())
        // The placeholder snapshot is a plain soft list; every binder tests for LazyList first.
        assertFalse(deltas[1].items is LazyList<*>)
        assertEquals(listOf(placeholder), deltas[1].items.softLoadedItems())
    }

    @Test
    fun placeholderIsVisibleThroughLoadedItemsAfterLazyMap() = runTest {
        // Integration contract with consumers: view-model tests read lists via softLoadedItems(),
        // so a placeholder that isn't Present there would be silently invisible.
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(
            source.lazyMap<String, String> { it.uppercase() }.ifEmpty { placeholder }
        ) {}

        assertEquals(listOf(placeholder), deltas[0].items.softLoadedItems())
    }

    @Test
    fun filterItemsThenIfEmptyShowsPlaceholderWhenNothingMatches() = runTest {
        val source = MutableStateFlow(delta("a", "bb"))

        val deltas = collectDriven(
            source.filterItems { it.length == 1 }.ifEmpty { placeholder }
        ) {
            step(source, delta("bb", "ccc", change = Change.Reload))
        }

        assertEquals(listOf("a"), deltas[0].items.softLoadedItems())
        assertEquals(listOf(placeholder), deltas.last().items.softLoadedItems())
    }

    @Test
    fun perSectionIfEmptyGivesPerSectionEmptyStates() = runTest {
        val rooms = MutableStateFlow(delta())
        val titles = MutableStateFlow(delta("t1"))

        val deltas = collectDriven(
            sectionedDeltaList(
                "titles" to titles,
                "rooms" to rooms.ifEmpty { placeholder }
            )
        ) {
            step(rooms, delta("r1", change = Change.Mutations(Mutation.Insert(0, 1))))
        }

        assertEquals(1, deltas[0].getItemCountAt(1))
        assertEquals(listOf(placeholder), deltas[0].sections[1].items.softLoadedItems())
        assertEquals(listOf("r1"), deltas.last().sections[1].items.softLoadedItems())
    }

    @Test
    fun repeatedEmptyTickDoesNotReloadTheWholeSectionedList() = runTest {
        val rooms = MutableStateFlow(delta())
        val titles = MutableStateFlow(delta("t1"))

        val deltas = collectDriven(
            sectionedDeltaList(
                "titles" to titles,
                "rooms" to rooms.ifEmpty { placeholder }
            )
        ) {
            // An empty repository re-hydrating must not reload the sibling sections.
            step(rooms, delta(change = Change.Reload))
            step(rooms, delta(change = Change.Reload))
        }

        assertEquals(1, deltas.size)
        assertIs<SectionedChange.Reload>(deltas[0].change)
    }

    @Test
    fun headerStaysVisibleWhilePlaceholderShows() = runTest {
        val source = MutableStateFlow(delta())

        val deltas = collectDriven(source.ifEmpty { placeholder }.header("H")) {}

        assertEquals(listOf("H", placeholder), deltas[0].items.softLoadedItems())
    }
}

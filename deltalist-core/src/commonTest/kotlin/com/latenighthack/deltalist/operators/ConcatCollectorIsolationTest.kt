@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcatCollectorIsolationTest {
    @Test
    fun concatCollectorsHaveIndependentHistory() = runTest {
        assertFlatIsolation { first, second -> first.concat(second) }
    }

    @Test
    fun concatSectionsCollectorsHaveIndependentHistory() = runTest {
        assertFlatIsolation { first, second -> concatSections(first, second) }
    }

    @Test
    fun sectionedCollectorsHaveIndependentHistory() = runTest {
        assertCollectorIsolation(
            compose = { first, second -> sectionedDeltaList("Pending" to first, "History" to second) },
            verify = { history, sources, editedSection, mutation ->
                history.assertSectionedOracle()
                val delta = history.last()
                assertEquals(listOf("Pending", "History"), delta.sections.map { it.header })
                assertEquals(sources, delta.sections.map { it.items.softLoadedItems() })
                val expected = if (editedSection == null) SectionedChange.Reload else {
                    SectionedChange.Items(editedSection, listOf(requireNotNull(mutation)))
                }
                assertEquals(expected, delta.change)
            }
        )
    }

    private suspend fun TestScope.assertFlatIsolation(
        compose: (DeltaList<Int>, DeltaList<Int>) -> DeltaList<Int>
    ) {
        assertCollectorIsolation(compose) { history, sources, editedSection, mutation ->
            history.assertFlatOracle()
            val delta = history.last()
            assertEquals(sources.flatten(), delta.items.softLoadedItems())
            val expected = if (editedSection == null) Change.Reload else {
                val offset = sources.take(editedSection).sumOf { it.size }
                Change.Mutations(requireNotNull(mutation).offsetBy(offset))
            }
            assertEquals(expected, delta.change)
        }
    }

    /** Every subscription below collects the same composed instance, including recollection. */
    private suspend fun <D> TestScope.assertCollectorIsolation(
        compose: (DeltaList<Int>, DeltaList<Int>) -> Flow<D>,
        verify: (List<D>, List<List<Int>>, Int?, Mutation?) -> Unit
    ) {
        val first = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))
        val sources = listOf(first, second)
        val composed = compose(first, second)
        val active = mutableListOf<MutableList<D>>()
        val jobs = mutableListOf<Job>()

        fun snapshots() = sources.map { it.value.items.softLoadedItems() }

        fun subscribe(): Pair<MutableList<D>, Job> {
            val previousSizes = active.map { it.size }
            val history = mutableListOf<D>()
            val job = launch { composed.collect { history.add(it) } }
            jobs.add(job)
            advanceUntilIdle()
            assertEquals(previousSizes, active.map { it.size }, "subscription must not emit to existing collectors")
            assertEquals(1, history.size)
            verify(history, snapshots(), null, null)
            active.add(history)
            return history to job
        }

        fun edit(section: Int, items: List<Int>, mutation: Mutation) {
            val previousSizes = active.map { it.size }
            // Drain after every edit so upstream conflation cannot hide a transition.
            step(sources[section], Delta(items, Change.Mutations(mutation)))
            active.forEachIndexed { index, history ->
                assertEquals(previousSizes[index] + 1, history.size)
                // Reconstruct each collector's entire history independently and require the
                // exact incremental change, even when a Reload could rebuild the same snapshot.
                verify(history, snapshots(), section, mutation)
            }
        }

        try {
            subscribe()
            val (cancelledHistory, cancelledJob) = subscribe()

            // Concurrent collectors, with alternating sources and changing concat offsets.
            edit(0, listOf(1, 2, 5), Mutation.Insert(2, 1))
            edit(1, listOf(3, 6, 4), Mutation.Insert(1, 1))
            edit(0, listOf(2, 5), Mutation.Remove(0, 1))

            // Both upstreams now carry mutations. A late collector must still start with Reload.
            subscribe()
            edit(1, listOf(6, 4), Mutation.Remove(0, 1))
            edit(0, listOf(2, 7), Mutation.Update(1, 1))

            cancelledJob.cancelAndJoin()
            active.removeAll { it === cancelledHistory }
            val cancelledSize = cancelledHistory.size
            edit(1, listOf(4, 6), Mutation.Move(0, 1))

            // Recollect after missing an edit, while the other two collectors remain active.
            subscribe()
            edit(0, listOf(8, 2, 7), Mutation.Insert(0, 1))
            edit(1, listOf(4, 9), Mutation.Update(1, 1))
            assertEquals(cancelledSize, cancelledHistory.size, "cancelled collector must stay stopped")
        } finally {
            jobs.forEach { it.cancelAndJoin() }
        }
    }
}

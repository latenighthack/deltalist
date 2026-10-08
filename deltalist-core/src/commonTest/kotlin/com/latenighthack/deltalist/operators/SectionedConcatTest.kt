@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SectionedConcatTest {

    // ==================== concatSections (flat) ====================

    @Test
    fun concatSections_alternatingSingleSourceMutations() = runTest {
        val a = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val b = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))
        val c = MutableStateFlow(Delta(listOf(5, 6), Change.Reload))

        val deltas = collectDriven(concatSections(a, b, c)) {
            step(b, Delta(listOf(3, 9, 4), Change.Mutations(Mutation.Insert(1, 1))))
            step(a, Delta(listOf(1), Change.Mutations(Mutation.Remove(1, 1))))
            step(c, Delta(listOf(5, 6, 7), Change.Mutations(Mutation.Insert(2, 1))))
        }

        deltas.assertFlatOracle()
        assertEquals(listOf(1, 3, 9, 4, 5, 6, 7), deltas.last().items.softLoadedItems())
    }

    @Test
    fun concatSections_sourceReloadSurfacesAsReload() = runTest {
        val a = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val b = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))

        val deltas = collectDriven(concatSections(a, b)) {
            step(a, Delta(listOf(1, 2, 8), Change.Mutations(Mutation.Insert(2, 1))))
            step(b, Delta(listOf(0), Change.Reload))
        }

        deltas.assertFlatOracle()
        assertTrue(deltas.last().change is Change.Reload)
        assertEquals(listOf(1, 2, 8, 0), deltas.last().items.softLoadedItems())
    }

    @Test
    fun concatSections_fuzz() = runTest {
        val seed = 0xABCDEFL
        val rng = Random(seed)
        repeat(500) { trial ->
            val sourceLists = Array(3) { s -> (0 until rng.nextInt(0, 3)).map { s * 100 + it } }
            var counter = 1_000
            val next = { counter++ }
            val sources = sourceLists.map { MutableStateFlow(Delta(it, Change.Reload)) }

            try {
                val deltas = collectDriven(concatSections(sources)) {
                    repeat(rng.nextInt(2, 8)) {
                        val s = rng.nextInt(sources.size)
                        val (nl, op) = randomFlatStep(sourceLists[s], rng, next)
                        sourceLists[s] = nl
                        step(sources[s], Delta(nl, Change.Mutations(op)))
                    }
                }
                deltas.assertFlatOracle()
                assertEquals(sourceLists.flatMap { it }, deltas.last().items.softLoadedItems())
            } catch (t: Throwable) {
                throw AssertionError("fuzz trial #$trial (seed=$seed) failed: ${t.message}")
            }
        }
    }

    // ==================== sectionedDeltaList (sectioned) ====================

    @Test
    fun sectionedDeltaList_singleSectionChangeIsItems() = runTest {
        val a = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val b = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))

        val deltas: List<SectionedDelta<String, Int>> = collectDriven(
            sectionedDeltaList("A" to a, "B" to b)
        ) {
            step(a, Delta(listOf(1, 2, 5), Change.Mutations(Mutation.Insert(2, 1))))
            step(b, Delta(listOf(3), Change.Mutations(Mutation.Remove(1, 1))))
        }

        deltas.assertSectionedOracle()

        val aChange = deltas[deltas.indexOfFirst { it.change is SectionedChange.Items && (it.change as SectionedChange.Items).section == 0 }].change
        assertIs<SectionedChange.Items>(aChange)
        assertEquals(0, aChange.section)

        val last = deltas.last().change
        assertIs<SectionedChange.Items>(last)
        assertEquals(1, last.section) // only section B (index 1) emitted on the last tick
    }

    @Test
    fun sectionedDeltaList_simultaneousMultiSectionChangeIsReload() = runTest {
        val a = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val b = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))

        val deltas = collectDriven(sectionedDeltaList("A" to a, "B" to b)) {
            a.value = Delta(listOf(1, 2, 5), Change.Mutations(Mutation.Insert(2, 1)))
            b.value = Delta(listOf(3, 4, 6), Change.Mutations(Mutation.Insert(2, 1)))
            advanceUntilIdle()
        }

        deltas.assertSectionedOracle()
        assertTrue(deltas.last().change is SectionedChange.Reload)
    }

    @Test
    fun sectionedDeltaListReloadsAfterBackpressureSkipsChildEmissions() = runTest {
        val begin = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val child = flow {
            emit(Delta(listOf("A"), Change.Reload))
            begin.await()
            emit(Delta(listOf("A", "B"), Change.Mutations(Mutation.Insert(1))))
            emit(Delta(listOf("A", "B", "C"), Change.Mutations(Mutation.Insert(2))))
            emit(Delta(listOf("A", "B", "C", "D"), Change.Mutations(Mutation.Insert(3))))
        }
        val deltas = mutableListOf<SectionedDelta<String, String>>()
        val job = launch {
            sectionedDeltaList("left" to child, "right" to flowOf(Delta(listOf("Z"), Change.Reload)))
                .collect { delta ->
                    deltas += delta
                    if (deltas.size == 1) {
                        begin.complete(Unit)
                        unblock.await()
                    }
                }
        }
        runCurrent()
        unblock.complete(Unit)
        runCurrent()
        job.cancelAndJoin()

        deltas.assertSectionedOracle()
        assertEquals(listOf("A", "B", "C", "D"), deltas.last().sections[0].items.softLoadedItems())
        assertTrue(deltas.drop(1).any { it.change is SectionedChange.Reload })
    }
}

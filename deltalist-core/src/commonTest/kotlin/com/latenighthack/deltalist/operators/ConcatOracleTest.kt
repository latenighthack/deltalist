@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oracle-based tests for [concat]. Every scenario folds the emitted [Change]s through
 * [applyChange] and asserts the reconstruction matches the carried snapshot — the check the
 * old `concat_mutationsInBothListsOffsetCorrectly` test omitted, which is why the stale-side
 * re-application bug went unnoticed.
 */
class ConcatOracleTest {

    /**
     * Regression lock for the confirmed bug: under `combine`, a single-side mutation must not
     * re-apply the *other* side's stale change. Before the fix this corrupts the list.
     */
    @Test
    fun alternatingSingleSideMutationsStayConsistent() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val deltas = collectDriven(first.concat(second)) {
            // first inserts 9 at index 3 -> first becomes [1,2,3,9]
            step(first, Delta(listOf(1, 2, 3, 9), Change.Mutations(Mutation.Insert(3, 1))))
            // second inserts 10 at index 3 -> second becomes [4,5,6,10]
            // (first.change is now the STALE Insert(3); it must NOT be re-applied)
            step(second, Delta(listOf(4, 5, 6, 10), Change.Mutations(Mutation.Insert(3, 1))))
        }

        deltas.assertFlatOracle()
        assertEquals(listOf(1, 2, 3, 9, 4, 5, 6, 10), deltas.last().items.softLoadedItems())
    }

    @Test
    fun simultaneousBothSideMutations() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val deltas = collectDriven(first.concat(second)) {
            // Set both before draining so combine sees both as emitters in one tick.
            first.value = Delta(listOf(1, 2, 10, 3), Change.Mutations(Mutation.Insert(2, 1)))
            second.value = Delta(listOf(4, 20, 5, 6), Change.Mutations(Mutation.Insert(1, 1)))
            advanceUntilIdle()
        }

        deltas.assertFlatOracle()
        assertEquals(listOf(1, 2, 10, 3, 4, 20, 5, 6), deltas.last().items.softLoadedItems())
    }

    @Test
    fun midStreamReloadFromOneSideReplacesOnlyItsRange() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(3, 4), Change.Reload))

        val deltas = collectDriven(first.concat(second)) {
            step(first, Delta(listOf(1, 2, 5), Change.Mutations(Mutation.Insert(2, 1))))
            step(second, Delta(listOf(9, 8, 7), Change.Reload))
        }

        deltas.assertFlatOracle()
        assertEquals(
            Change.Mutations(listOf(Mutation.Remove(3, 2), Mutation.Insert(3, 3))),
            deltas.last().change,
            "a source Reload replaces only that source's range",
        )
        assertEquals(listOf(1, 2, 5, 9, 8, 7), deltas.last().items.softLoadedItems())
    }

    @Test
    fun headerEmitsIncrementalMutationsNotReload() = runTest {
        val body = MutableStateFlow(Delta(listOf(2, 3, 4), Change.Reload))

        val deltas = collectDriven(body.header(1)) {
            step(body, Delta(listOf(2, 3, 4, 5), Change.Mutations(Mutation.Insert(3, 1))))
        }

        deltas.assertFlatOracle()
        assertEquals(listOf(1, 2, 3, 4, 5), deltas.last().items.softLoadedItems())
        // The body change must propagate incrementally, offset past the header.
        val last = deltas.last().change
        assertTrue(last is Change.Mutations, "header() must stay incremental, was $last")
        assertEquals(listOf(Mutation.Insert(4, 1)), last.operations)
    }

    @Test
    fun footerEmitsIncrementalMutationsNotReload() = runTest {
        val body = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))

        val deltas = collectDriven(body.footer(99)) {
            step(body, Delta(listOf(1, 2), Change.Mutations(Mutation.Remove(2, 1))))
        }

        deltas.assertFlatOracle()
        assertEquals(listOf(1, 2, 99), deltas.last().items.softLoadedItems())
        val last = deltas.last().change
        assertTrue(last is Change.Mutations, "footer() must stay incremental, was $last")
        assertEquals(listOf(Mutation.Remove(2, 1)), last.operations)
    }

    @Test
    fun fuzzAlternatingSources() = runTest {
        val seed = 0xC0FFEEL
        val rng = Random(seed)
        repeat(1_000) { trial ->
            var firstList = (0 until rng.nextInt(0, 4)).map { it }
            var secondList = (100 until 100 + rng.nextInt(0, 4)).map { it }
            var counter = 1_000
            val next = { counter++ }

            val first = MutableStateFlow(Delta(firstList, Change.Reload))
            val second = MutableStateFlow(Delta(secondList, Change.Reload))

            try {
                val deltas = collectDriven(first.concat(second)) {
                    repeat(rng.nextInt(2, 8)) {
                        if (rng.nextBoolean()) {
                            val (nl, op) = randomFlatStep(firstList, rng, next)
                            firstList = nl
                            step(first, Delta(nl, Change.Mutations(op)))
                        } else {
                            val (nl, op) = randomFlatStep(secondList, rng, next)
                            secondList = nl
                            step(second, Delta(nl, Change.Mutations(op)))
                        }
                    }
                }
                deltas.assertFlatOracle()
                assertEquals(firstList + secondList, deltas.last().items.softLoadedItems())
            } catch (t: Throwable) {
                throw AssertionError("fuzz trial #$trial (seed=$seed) failed: ${t.message}")
            }
        }
    }
}

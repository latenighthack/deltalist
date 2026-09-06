@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz oracle for [ifEmpty]. The operator rewrites changes only at emptiness transitions, so the
 * risk is a mutation passed through against a base the binder doesn't actually hold — exactly what
 * [assertFlatOracle] catches. The source is driven to empty often, so transitions dominate.
 */
class IfEmptyOracleTest {

    private val placeholder = -1

    @Test
    fun randomEmptyRefillSequencesStayConsistent() = runTest {
        val rng = Random(20260903)
        var next = 1
        var list = emptyList<Int>()
        val source = MutableStateFlow(Delta(list, Change.Reload))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            repeat(200) {
                if (list.isNotEmpty() && rng.nextInt(6) == 0) {
                    // Deliberately bias toward emptying: transitions are the interesting path.
                    val op = Mutation.Remove(0, list.size)
                    list = emptyList()
                    step(source, Delta(list, Change.Mutations(op)))
                } else {
                    val (updated, op) = randomFlatStep(list, rng, { next++ })
                    list = updated
                    step(source, Delta(list, Change.Mutations(op)))
                }
            }
        }

        deltas.assertFlatOracle()

        // Whatever the sequence ended on, the tail snapshot is either the real list or exactly the
        // placeholder — never both, and never a stale mixture.
        val tail = deltas.last().items.softLoadedItems()
        assertEquals(if (list.isEmpty()) listOf(placeholder) else list, tail)
        assertTrue(deltas.none { it.items.softLoadedItems().let { s -> s.size > 1 && s.contains(placeholder) } })
    }

    @Test
    fun repeatedEmptyTicksNeverEmitDuplicates() = runTest {
        val rng = Random(7)
        var next = 1
        var list = emptyList<Int>()
        val source = MutableStateFlow(Delta(list, Change.Reload))

        val deltas = collectDriven(source.ifEmpty { placeholder }) {
            repeat(60) {
                if (list.isEmpty()) {
                    // Re-emit "still empty" repeatedly (a cold repository re-hydrating).
                    step(source, Delta(list, Change.Reload))
                    if (rng.nextBoolean()) {
                        list = listOf(next++)
                        step(source, Delta(list, Change.Mutations(Mutation.Insert(0, 1))))
                    }
                } else {
                    list = emptyList()
                    step(source, Delta(list, Change.Mutations(Mutation.Remove(0, 1))))
                }
            }
        }

        deltas.assertFlatOracle()
        // No two consecutive emissions may both be the placeholder snapshot.
        val snapshots = deltas.map { it.items.softLoadedItems() }
        for (i in 1 until snapshots.size) {
            assertTrue(
                !(snapshots[i] == listOf(placeholder) && snapshots[i - 1] == listOf(placeholder)),
                "duplicate placeholder emission at #$i"
            )
        }
    }
}

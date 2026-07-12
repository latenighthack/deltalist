package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.applyChange
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oracle for [lazyMapPreviousNext]: a binder that only rebinds mutated indices
 * ([applyChange]) must end up with exactly the same rendering as a full recompute of the
 * neighbor-aware transform over the new snapshot. This is what guarantees chat-style
 * bubble grouping stays correct when the source emits minimal diffs.
 */
class LazyMapNeighborsOracleTest {

    private data class Item(val id: Int, val version: Int)

    /** The neighbor-sensitive rendering: depends on previous/current/next identity+content. */
    private fun render(current: Item, previous: Item?, next: Item?): String =
        "${previous?.id}<${current.id}v${current.version}>${next?.id}"

    private fun renderAll(items: List<Item>): List<String> =
        items.mapIndexed { i, item -> render(item, items.getOrNull(i - 1), items.getOrNull(i + 1)) }

    private suspend fun verifySequence(states: List<List<Item>>) {
        val deltas: List<Delta<String>> = flow { states.forEach { emit(it) } }
            .asDeltaList { it.id }
            .lazyMapPreviousNext { current, previous, next -> render(current, previous, next) }
            .toList()

        assertEquals(states.size, deltas.size, "one delta per emission")

        var screen = emptyList<String>()
        for ((i, delta) in deltas.withIndex()) {
            screen = applyChange(screen, delta)
            assertEquals(
                renderAll(states[i]), screen,
                "delta #$i ${delta.change} left stale neighbor renderings for ${states[i]}"
            )
        }
    }

    @Test
    fun append_updates_previous_tail() = runTest {
        // The classic chat case: appending a message must rebind the old tail (its `next`
        // changed) even though the source diff is a single Insert.
        verifySequence(
            listOf(
                listOf(Item(1, 0)),
                listOf(Item(1, 0), Item(2, 0)),
                listOf(Item(1, 0), Item(2, 0), Item(3, 0)),
            )
        )
        val tail = flow {
            emit(listOf(Item(1, 0)))
            emit(listOf(Item(1, 0), Item(2, 0)))
        }.asDeltaList { it.id }
            .lazyMapPreviousNext { c, p, n -> render(c, p, n) }
            .toList()
            .last()
        val ops = (tail.change as Change.Mutations).operations
        assertTrue(
            ops.any { it is com.latenighthack.deltalist.Mutation.Update && it.index == 0 },
            "append must emit an Update for the previous tail, got $ops"
        )
    }

    @Test
    fun remove_updates_both_seam_neighbors() = runTest {
        verifySequence(
            listOf(
                listOf(Item(1, 0), Item(2, 0), Item(3, 0)),
                listOf(Item(1, 0), Item(3, 0)),
                listOf(Item(3, 0)),
            )
        )
    }

    @Test
    fun update_rebinds_neighbors() = runTest {
        verifySequence(
            listOf(
                listOf(Item(1, 0), Item(2, 0), Item(3, 0)),
                listOf(Item(1, 0), Item(2, 1), Item(3, 0)),
            )
        )
    }

    @Test
    fun move_rebinds_seams_and_moved_item() = runTest {
        verifySequence(
            listOf(
                listOf(Item(1, 0), Item(2, 0), Item(3, 0), Item(4, 0)),
                listOf(Item(2, 0), Item(3, 0), Item(1, 0), Item(4, 0)),
                listOf(Item(4, 0), Item(2, 0), Item(3, 0), Item(1, 0)),
            )
        )
    }

    @Test
    fun empty_and_reload_transitions() = runTest {
        verifySequence(
            listOf(
                emptyList(),
                listOf(Item(1, 0)),
                emptyList(),
                listOf(Item(2, 0), Item(3, 0)),
            )
        )
    }

    @Test
    fun lazyMapPrevious_matches_recompute() = runTest {
        val states = listOf(
            listOf(Item(1, 0), Item(2, 0)),
            listOf(Item(2, 0), Item(1, 0), Item(3, 0)),
        )
        val deltas = flow { states.forEach { emit(it) } }
            .asDeltaList { it.id }
            .lazyMapPrevious { c, p -> "${p?.id}<${c.id}" }
            .toList()

        var screen = emptyList<String>()
        for ((i, delta) in deltas.withIndex()) {
            screen = applyChange(screen, delta)
            assertEquals(
                states[i].mapIndexed { j, item -> "${states[i].getOrNull(j - 1)?.id}<${item.id}" },
                screen,
                "delta #$i stale"
            )
        }
    }

    @Test
    fun randomized_fuzz() = runTest {
        val random = Random(42)
        repeat(200) { trial ->
            var nextId = 0
            var current = List(random.nextInt(0, 8)) { Item(nextId++, 0) }
            val states = mutableListOf(current)
            repeat(random.nextInt(1, 6)) {
                val working = current.toMutableList()
                repeat(random.nextInt(1, 4)) {
                    when (random.nextInt(4)) {
                        0 -> working.add(random.nextInt(0, working.size + 1), Item(nextId++, 0))
                        1 -> if (working.isNotEmpty()) working.removeAt(random.nextInt(working.size))
                        2 -> if (working.isNotEmpty()) {
                            val i = random.nextInt(working.size)
                            working[i] = working[i].copy(version = working[i].version + 1)
                        }
                        3 -> if (working.size > 1) {
                            val from = random.nextInt(working.size)
                            val item = working.removeAt(from)
                            working.add(random.nextInt(0, working.size + 1), item)
                        }
                    }
                }
                current = working.toList()
                states.add(current)
            }
            try {
                verifySequence(states)
            } catch (e: AssertionError) {
                throw AssertionError("trial #$trial failed for states $states: ${e.message}", e)
            }
        }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.lazyMap
import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The flat Swift bridge, [Delta.trackStableIds]: one section's ids for a flat list. */
class FlatStableIdsTest {
    private fun SectionedIds.items(size: Int) = (0 until size).map { itemId(0, it) }

    @Test fun idsAreDistinctAndFollowRunningMutations() {
        val first = Delta(listOf(1, 2, 3).asSoftList(), Change.Reload).trackStableIds(null)
        val (one, two, three) = first.items(3)
        assertEquals(3, setOf(one, two, three).size)

        val next = Delta(
            listOf(3, 9, 1).asSoftList(),
            Change.Mutations(Mutation.Remove(1), Mutation.Move(1, 0), Mutation.Insert(1), Mutation.Update(2)),
        ).trackStableIds(first)

        val ids = next.items(3)
        assertEquals(listOf(three, one), listOf(ids[0], ids[2]))
        assertTrue(ids[1] !in setOf(one, two, three))
    }

    @Test fun reloadRegeneratesIds() {
        val first = Delta(listOf(1, 2).asSoftList(), Change.Reload).trackStableIds(null)
        val next = Delta(listOf(1, 2).asSoftList(), Change.Reload).trackStableIds(first)
        assertTrue(next.items(2).none { it in first.items(2) })
    }

    @Test fun matchesWithStableIds() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val raw = mutableListOf<Delta<String>>()
        val stable = mutableListOf<Delta<StableItem<String>>>()
        val rawJob = launch { source.collect { raw.add(it) } }
        val stableJob = launch { source.withStableIds().collect { stable.add(it) } }
        runCurrent()
        source.move(2, 0); runCurrent()
        source.insert(1, "n"); runCurrent()
        source.set(0, "c2"); runCurrent()
        source.removeAt(2); runCurrent()
        rawJob.cancel()
        stableJob.cancel()

        // Both start from their own counter, so the ids must correspond one to one across emissions.
        val correspondence = mutableMapOf<Int, Int>()
        var previous: SectionedIds? = null
        raw.zip(stable).forEach { (delta, expected) ->
            val tracked = delta.trackStableIds(previous)
            for (index in 0 until delta.items.size) {
                val expectedId = expected.items[index].stableId
                assertEquals(expectedId, correspondence.getOrPut(tracked.itemId(0, index)) { expectedId })
            }
            previous = tracked
        }
        assertEquals(correspondence.size, correspondence.values.toSet().size)
    }

    @Test fun lazilyMappedRowsAreNotBuilt() = runTest {
        var built = 0
        val deltas = mutableDeltaListOf(listOf("a", "b", "c"))
            .lazyMap { built++; "row:$it" }
            .take(1)
            .toList()

        val ids = deltas.single().trackStableIds(null)
        assertEquals(3, ids.items(3).toSet().size)
        assertEquals(0, built)
    }
}

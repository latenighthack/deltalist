package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FilterPlaceholderMutationTest {
    private class Estimated(private val loaded: List<Int>, override val size: Int) : AbstractSoftList<Int>() {
        var requests = 0
        override fun softGet(index: Int): SoftValue<Int>? = when {
            index !in 0 until size -> null
            index < loaded.size -> SoftValue.Present(loaded[index])
            else -> SoftValue.NotLoaded { requests++ }
        }
    }

    @Test fun staticFilterPreservesRowsBeforePlaceholders() = verify(dynamic = false)
    @Test fun dynamicFilterPreservesRowsBeforePlaceholders() = verify(dynamic = true)

    private fun verify(dynamic: Boolean) = runTest {
        val snapshots = listOf(
            Estimated(listOf(2, 4), 4),
            Estimated(listOf(6, 2, 4), 5),
            Estimated(listOf(6, 2, 8, 4), 6),
            Estimated(listOf(6, 2, 8, 4, 10), 6)
        )
        val source = MutableStateFlow(Delta<Int>(snapshots[0], Change.Reload))
        val predicate: (Int) -> Boolean = { it % 2 == 0 }
        val filtered = if (dynamic) source.filterItemsDynamic(flowOf(predicate)) else source.filterItems(predicate)
        val deltas = collectDriven(filtered) {
            step(source, Delta(snapshots[1], Change.Mutations(Mutation.Insert(0))))
            step(source, Delta(snapshots[2], Change.Mutations(Mutation.Insert(2))))
            // Page completion fills a placeholder at the current loaded boundary.
            step(source, Delta(snapshots[3], Change.Mutations(Mutation.Update(4))))
        }
        fun slots(delta: Delta<Int>) = (0 until delta.items.size).map {
            (delta.items.softGet(it) as? SoftValue.Present)?.value
        }
        var rendered = slots(deltas.first())
        for (delta in deltas.drop(1)) {
            rendered = applyChange(rendered, delta.change, slots(delta))
            assertEquals(slots(delta), rendered)
        }
        assertEquals(listOf(Mutation.Insert(0)), assertIs<Change.Mutations>(deltas[1].change).operations)
        assertEquals(listOf(Mutation.Update(4)), assertIs<Change.Mutations>(deltas.last().change).operations)
        assertEquals(0, snapshots.sumOf { it.requests })
    }
}

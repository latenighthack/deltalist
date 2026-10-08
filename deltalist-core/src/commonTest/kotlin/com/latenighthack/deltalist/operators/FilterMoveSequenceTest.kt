package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterMoveSequenceTest {
    @Test fun staticFilterTracksExcludedMoves() = verify(dynamic = false)
    @Test fun dynamicFilterTracksExcludedMoves() = verify(dynamic = true)

    private fun verify(dynamic: Boolean) = runTest {
        val source = MutableStateFlow(listOf(2, 4, 1, 6))
        val predicate: (Int) -> Boolean = { it % 2 == 0 }
        val filtered = if (dynamic) source.asDeltaList().filterItemsDynamic(flowOf(predicate))
            else source.asDeltaList().filterItems(predicate)
        val deltas = collectDriven(filtered) {
            // Diff emits Move(2, 0) for excluded 1, then Move(2, 1) for retained 4.
            step(source, listOf(1, 4, 2, 6))
            step(source, listOf(6, 1, 2, 4))
        }
        deltas.assertFlatOracle()
        assertEquals(listOf(6, 2, 4), deltas.last().items.softLoadedItems())
        assertEquals(listOf(4, 2, 6), deltas[1].items.softLoadedItems())
    }
}

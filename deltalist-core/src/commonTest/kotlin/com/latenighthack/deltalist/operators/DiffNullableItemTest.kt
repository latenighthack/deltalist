package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.applyChange
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffNullableItemTest {
    @Test fun nullIsARealRetainedItemValue() = runTest {
        val snapshots = listOf(listOf<String?>(null), listOf("ready"), listOf(null), listOf(null))
        val deltas = flowOf(*snapshots.toTypedArray()).asDeltaList { "row" }.toList()
        var rendered = emptyList<String?>()
        deltas.forEachIndexed { index, delta ->
            rendered = applyChange(rendered, delta)
            assertEquals(snapshots[index], rendered)
        }
        assertEquals(Change.Mutations(Mutation.Update(0)), deltas[1].change)
        assertEquals(Change.Mutations(Mutation.Update(0)), deltas[2].change)
        assertEquals(Change.Mutations(emptyList()), deltas[3].change)
    }
}

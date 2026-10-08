package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupByTest {

    private data class Row(val key: Int, val v: Int)

    @Test
    fun groupsItemsByKeyPreservingOrder() = runTest {
        val rows = listOf(Row(0, 1), Row(1, 2), Row(0, 3), Row(2, 4), Row(1, 5))
        val source = MutableStateFlow(Delta(rows, Change.Reload))

        val deltas: List<SectionedDelta<Int, Row>> = collectDriven(source.groupBy { it.key }) { }

        val sections = deltas.last().sections
        assertEquals(listOf(0, 1, 2), sections.map { it.header }) // first-seen key order
        assertEquals(listOf(Row(0, 1), Row(0, 3)), sections[0].items.softLoadedItems())
        assertEquals(listOf(Row(1, 2), Row(1, 5)), sections[1].items.softLoadedItems())
        assertEquals(listOf(Row(2, 4)), sections[2].items.softLoadedItems())
    }

    @Test
    fun inPlaceUpdateKeepingGroupShapeIsSingleSectionItems() = runTest {
        val rows = listOf(Row(0, 1), Row(1, 2), Row(0, 3))
        val source = MutableStateFlow(Delta(rows, Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            // Update the item at flat index 2 (Row(0,3) -> Row(0,99)); group shape unchanged.
            step(source, Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 99)), Change.Mutations(Mutation.Update(2, 1))))
        }

        deltas.assertSectionedOracle()
        val last = deltas.last().change
        assertIs<SectionedChange.Items>(last)
        assertEquals(0, last.section) // key 0's section changed
        assertEquals(listOf(Row(0, 1), Row(0, 99)), deltas.last().sections[0].items.softLoadedItems())
    }

    @Test
    fun insertThatChangesGroupSizeFallsBackToReload() = runTest {
        val rows = listOf(Row(0, 1), Row(1, 2))
        val source = MutableStateFlow(Delta(rows, Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(0, 9), Row(1, 2)), Change.Mutations(Mutation.Insert(1, 1))))
        }

        deltas.assertSectionedOracle()
        assertTrue(deltas.last().change is SectionedChange.Reload)
    }

    @Test
    fun headerMapperOverloadGroupsAndMaps() = runTest {
        val rows = listOf(Row(0, 1), Row(1, 2), Row(0, 3))
        val source = MutableStateFlow(Delta(rows, Change.Reload))

        val deltas = collectDriven(
            source.groupBy(keySelector = { it.key }, headerMapper = { k, items -> "key=$k(${items.size})" })
        ) { }

        assertEquals(listOf("key=0(2)", "key=1(1)"), deltas.last().sections.map { it.header })
    }

    @Test
    fun flattenCrossCheckIsFlatOracleConsistent() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2)), Change.Reload))

        val flat = source.groupBy { it.key }.flattenItems()

        val deltas = collectDriven(flat) {
            step(source, Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 3)), Change.Mutations(Mutation.Insert(2, 1))))
            step(source, Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 99)), Change.Mutations(Mutation.Update(2, 1))))
        }

        deltas.assertFlatOracle()
    }

    @Test
    fun derivedHeaderChangesAreDeliveredWithItemUpdates() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 2), Row(1, 1)), Change.Reload))
        val flat = source.groupBy(
            keySelector = { it.key },
            headerMapper = { key, items -> "$key:${items.sumOf { it.v }}" }
        ).flatten(header = { "H:$it" }, item = { "I:${it.v}" })
        val deltas = collectDriven(flat) {
            step(source, Delta(listOf(Row(0, 4), Row(1, 1)), Change.Mutations(Mutation.Update(0))))
        }
        deltas.assertFlatOracle()
        assertIs<Change.Reload>(deltas.last().change)
        assertEquals(listOf("H:0:4", "I:4", "H:1:1", "I:1"), deltas.last().items.softLoadedItems())
    }

    @Test
    fun unchangedMappedHeadersKeepIncrementalItemChanges() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 2)), Change.Reload))
        val deltas = collectDriven(source.groupBy(
            keySelector = { it.key },
            headerMapper = { key, _ -> "group:$key" }
        )) {
            step(source, Delta(listOf(Row(0, 4)), Change.Mutations(Mutation.Update(0))))
        }
        deltas.assertSectionedOracle()
        assertIs<SectionedChange.Items>(deltas.last().change)
    }
}

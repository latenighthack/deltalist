package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SectionMutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedIds
import com.latenighthack.deltalist.SectionedStableIds
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.normalizeMutations
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
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
    fun insertInsideOneGroupIsItemsInThatSection() = runTest {
        val rows = listOf(Row(0, 1), Row(1, 2))
        val source = MutableStateFlow(Delta(rows, Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(0, 9), Row(1, 2)), Change.Mutations(Mutation.Insert(1, 1))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Items(0, Mutation.Insert(1)), deltas.last().change)
    }

    @Test
    fun removeInsideOneGroupIsItemsInThatSection() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2), Row(1, 3), Row(0, 4)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(1, 3), Row(0, 4)), Change.Mutations(Mutation.Remove(1))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Items(1, Mutation.Remove(0)), deltas.last().change)
    }

    @Test
    fun sectionAppearingKeepsOtherItemIds() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(2, 2), Row(0, 3)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(1, 9), Row(2, 2), Row(0, 3)), Change.Mutations(Mutation.Insert(1))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(SectionMutation.Insert(1)), deltas.last().change)
        val ids = deltas.map(SectionedStableIds()::apply)
        assertEquals(listOf(0, 1, 2), deltas.last().sections.map { it.header })
        assertEquals(ids[0].itemIds(0, 2) + ids[0].itemIds(1, 1), ids[1].itemIds(0, 2) + ids[1].itemIds(2, 1))
        assertEquals(listOf(ids[0].sectionIds[0], ids[0].sectionIds[1]), listOf(ids[1].sectionIds[0], ids[1].sectionIds[2]))
    }

    @Test
    fun sectionDisappearingKeepsOtherItemIds() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2), Row(2, 3), Row(1, 4), Row(2, 5)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            // A filter hiding every key-1 row removes them one at a time, from the end.
            step(source, Delta(listOf(Row(0, 1), Row(2, 3), Row(2, 5)), Change.Mutations(Mutation.Remove(3), Mutation.Remove(1))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(SectionMutation.Remove(1)), deltas.last().change)
        val ids = deltas.map(SectionedStableIds()::apply)
        assertEquals(ids[0].itemIds(0, 1) + ids[0].itemIds(2, 2), ids[1].itemIds(0, 1) + ids[1].itemIds(1, 2))
    }

    @Test
    fun sectionsComingAndGoingTogetherAreOneSectionsChange() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2), Row(2, 3)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            // The only key-1 row becomes key 3, which is new.
            step(source, Delta(listOf(Row(0, 1), Row(3, 2), Row(2, 3)), Change.Mutations(Mutation.Update(1))))
            step(source, Delta(listOf(Row(0, 1), Row(3, 2), Row(2, 3), Row(4, 4), Row(5, 5)), Change.Mutations(Mutation.Insert(3, 2))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(SectionMutation.Remove(1), SectionMutation.Insert(1)), deltas[1].change)
        assertEquals(SectionedChange.Sections(SectionMutation.Insert(3, 2)), deltas[2].change)
    }

    @Test
    fun editsInSeveralGroupsReload() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 7), Row(1, 8)), Change.Mutations(Mutation.Update(0, 2))))
        }

        deltas.assertSectionedOracle()
        assertIs<SectionedChange.Reload>(deltas.last().change)
    }

    @Test
    fun movingTheOnlyItemOfAGroupIntoAnotherReloads() = runTest {
        // A group disappearing while another gains the item needs two kinds of change.
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 3)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(0, 2), Row(0, 3)), Change.Mutations(Mutation.Update(1))))
        }

        deltas.assertSectionedOracle()
        assertIs<SectionedChange.Reload>(deltas.last().change)
    }

    @Test
    fun keyOrderChangeReloads() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(1, 2), Row(0, 1)), Change.Mutations(Mutation.Move(1, 0))))
        }

        deltas.assertSectionedOracle()
        assertIs<SectionedChange.Reload>(deltas.last().change)
    }

    @Test
    fun movesInsideOneGroupAreItems() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 3), Row(0, 4)), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(listOf(Row(0, 1), Row(1, 2), Row(0, 4), Row(0, 3)), Change.Mutations(Mutation.Move(3, 2))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Items(0, Mutation.Move(2, 1)), deltas.last().change)
    }

    @Test
    fun changesOutsideTheLoadedPrefixLeaveGroupsAlone() = runTest {
        val source = MutableStateFlow(Delta(prefixed(listOf(Row(0, 1), Row(1, 2)), unloaded = 5), Change.Reload))

        val deltas = collectDriven(source.groupBy { it.key }) {
            step(source, Delta(prefixed(listOf(Row(0, 1), Row(1, 2)), unloaded = 3), Change.Mutations(Mutation.Remove(4, 2))))
            // Loading the first missing position extends key 0's group.
            step(source, Delta(prefixed(listOf(Row(0, 1), Row(1, 2), Row(0, 3)), unloaded = 2), Change.Mutations(Mutation.Update(2))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(emptyList()), deltas[1].change)
        assertEquals(SectionedChange.Items(0, Mutation.Insert(1)), deltas[2].change)
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
    fun mappedHeadersOfUntouchedGroupsStayWhenAGroupAppears() = runTest {
        val source = MutableStateFlow(Delta(listOf(Row(0, 2)), Change.Reload))
        val deltas = collectDriven(source.groupBy(
            keySelector = { it.key },
            headerMapper = { key, items -> "$key:${items.size}" }
        )) {
            step(source, Delta(listOf(Row(0, 2), Row(1, 1)), Change.Mutations(Mutation.Insert(1))))
        }
        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(SectionMutation.Insert(1)), deltas.last().change)
    }

    @Test
    fun fuzzLoadedSourceIsOracleConsistent() = runTest {
        val seed = 0x6B0F
        val rng = Random(seed)
        val kinds = mutableMapOf<String, Int>()
        repeat(300) { trial ->
            var counter = 0
            var rows = List(rng.nextInt(0, 6)) { Row(rng.nextInt(4), counter++) }
            val source = MutableStateFlow(Delta(rows, Change.Reload))
            try {
                val deltas = collectDriven(source.groupBy { it.key }) {
                    repeat(rng.nextInt(1, 10)) {
                        val (next, operations) = randomRowSteps(rows, rng) { Row(rng.nextInt(4), counter++) }
                        rows = next
                        step(source, Delta(rows, Change.Mutations(operations)))
                    }
                }
                deltas.assertSectionedOracle()
                deltas.drop(1).forEach { val kind = it.change::class.simpleName!!; kinds[kind] = (kinds[kind] ?: 0) + 1 }
                deltas.assertIdsSurviveIncrementalChanges()
            } catch (t: Throwable) {
                throw AssertionError("fuzz trial #$trial (seed=$seed) failed: ${t.message}", t)
            }
        }
        // Most random edits stay inside one group or only add and remove groups.
        assertTrue(kinds.getValue("Items") + kinds.getValue("Sections") > kinds.getValue("Reload"), "$kinds")
    }

    @Test
    fun fuzzSoftSourceIsOracleConsistent() = runTest {
        val seed = 0x50F7
        val rng = Random(seed)
        val kinds = mutableMapOf<String, Int>()
        repeat(300) { trial ->
            var counter = 0
            // null is an unloaded position.
            var slots: List<Row?> = List(rng.nextInt(0, 8)) { if (rng.nextInt(4) == 0) null else Row(rng.nextInt(3), counter++) }
            val source = MutableStateFlow(Delta(SlotList(slots), Change.Reload))
            try {
                val deltas = collectDriven(source.groupBy(keySelector = { it.key }, headerMapper = { key, _ -> "group $key" })) {
                    repeat(rng.nextInt(1, 10)) {
                        val (next, operations) = randomRowSteps(slots, rng) { if (rng.nextInt(4) == 0) null else Row(rng.nextInt(3), counter++) }
                        slots = next
                        step(source, Delta(SlotList(slots), Change.Mutations(operations)))
                    }
                }
                deltas.assertSectionedOracle()
                deltas.drop(1).forEach { val kind = it.change::class.simpleName!!; kinds[kind] = (kinds[kind] ?: 0) + 1 }
                deltas.assertIdsSurviveIncrementalChanges()
            } catch (t: Throwable) {
                throw AssertionError("soft fuzz trial #$trial (seed=$seed) failed: ${t.message}", t)
            }
        }
        // Most random edits stay inside one group or only add and remove groups.
        assertTrue(kinds.getValue("Items") + kinds.getValue("Sections") > kinds.getValue("Reload"), "$kinds")
    }

    @Test
    fun fuzzFlattenedGroupsAreFlatOracleConsistent() = runTest {
        val seed = 0xF1A7
        val rng = Random(seed)
        repeat(200) { trial ->
            var counter = 0
            var rows = List(rng.nextInt(0, 6)) { Row(rng.nextInt(3), counter++) }
            val source = MutableStateFlow(Delta(rows, Change.Reload))
            val flat = source.groupBy(keySelector = { it.key }, headerMapper = { key, items -> "$key:${items.size}" })
                .flatten(header = { "H:$it" }, item = { "I:${it.v}" })
            try {
                val deltas = collectDriven(flat) {
                    repeat(rng.nextInt(1, 8)) {
                        val (next, operations) = randomRowSteps(rows, rng) { Row(rng.nextInt(3), counter++) }
                        rows = next
                        step(source, Delta(rows, Change.Mutations(operations)))
                    }
                }
                deltas.assertFlatOracle()
            } catch (t: Throwable) {
                throw AssertionError("flatten fuzz trial #$trial (seed=$seed) failed: ${t.message}", t)
            }
        }
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

    private fun SectionedIds.itemIds(section: Int, size: Int) = (0 until size).map { itemId(section, it) }

    /** Items that are still present keep their stable id through every non-reload change. */
    private fun <S> List<SectionedDelta<S, Row>>.assertIdsSurviveIncrementalChanges() {
        val tracker = SectionedStableIds()
        var previous: Map<Row, Int> = emptyMap()
        for (delta in this) {
            val ids = tracker.apply(delta)
            val current = buildMap {
                delta.sections.forEachIndexed { s, section ->
                    section.items.softLoadedItems().forEachIndexed { i, row -> put(row, ids.itemId(s, i)) }
                }
            }
            if (delta.change !is SectionedChange.Reload) {
                for ((row, id) in current) previous[row]?.let { assertEquals(it, id, "id of $row after ${delta.change}") }
            }
            previous = current
        }
    }

    /** A positional snapshot whose null slots are unloaded. */
    private class SlotList<T : Any>(private val slots: List<T?>) : AbstractSoftList<T>() {
        override val size: Int get() = slots.size
        override fun softGet(index: Int): SoftValue<T>? =
            if (index !in slots.indices) null else slots[index]?.let { SoftValue.Present(it) } ?: SoftValue.NotLoaded()
    }

    private fun prefixed(loaded: List<Row>, unloaded: Int): SoftList<Row> = SlotList(loaded + List(unloaded) { null })

    /** One to three random edits, normalized into one valid running-coordinate change. */
    private fun <T> randomRowSteps(list: List<T>, rng: Random, next: () -> T): Pair<List<T>, List<Mutation>> {
        val working = list.toMutableList()
        val operations = mutableListOf<Mutation>()
        repeat(rng.nextInt(1, 4)) {
            val choice = if (working.isEmpty()) 0 else rng.nextInt(4)
            when (choice) {
                0 -> {
                    val index = rng.nextInt(working.size + 1)
                    working.add(index, next())
                    operations += Mutation.Insert(index)
                }
                1 -> {
                    val index = rng.nextInt(working.size)
                    working.removeAt(index)
                    operations += Mutation.Remove(index)
                }
                2 -> {
                    val index = rng.nextInt(working.size)
                    working[index] = next()
                    operations += Mutation.Update(index)
                }
                else -> {
                    val from = rng.nextInt(working.size)
                    val to = rng.nextInt(working.size)
                    working.add(to, working.removeAt(from))
                    operations += Mutation.Move(from, to)
                }
            }
        }
        return working.toList() to normalizeMutations(list.size, operations)
    }
}

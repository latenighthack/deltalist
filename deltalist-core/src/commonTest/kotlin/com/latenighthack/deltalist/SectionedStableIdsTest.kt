@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.sectionedDeltaList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SectionedStableIdsTest {
    private fun SectionedIds.items(section: Int, size: Int) = (0 until size).map { itemId(section, it) }

    @Test fun itemIdsFollowRunningMutations() {
        val tracker = SectionedStableIds()
        val first = tracker.apply(SectionedDelta(listOf(Section("A", listOf(1, 2, 3))), SectionedChange.Reload))
        val (one, two, three) = first.items(0, 3)

        val next = tracker.apply(SectionedDelta(
            listOf(Section("A", listOf(3, 9, 1))),
            SectionedChange.Items(0, Mutation.Remove(1), Mutation.Move(1, 0), Mutation.Insert(1), Mutation.Update(2))
        ))

        val ids = next.items(0, 3)
        assertEquals(listOf(three, one), listOf(ids[0], ids[2]))
        assertTrue(ids[1] !in setOf(one, two, three))
        assertEquals(first.sectionIds, next.sectionIds)
    }

    @Test fun sectionIdsFollowSectionMutationsAndKeepTheirItems() {
        val tracker = SectionedStableIds()
        val first = tracker.apply(SectionedDelta(
            listOf(Section("A", listOf(1)), Section("B", listOf(2, 3))),
            SectionedChange.Reload
        ))
        val (a, b) = first.sectionIds
        val bItems = first.items(1, 2)

        val next = tracker.apply(SectionedDelta(
            listOf(Section("B2", listOf(2, 3)), Section("C", listOf(4)), Section("A", listOf(1))),
            SectionedChange.Sections(SectionMutation.Move(1, 0), SectionMutation.Update(0), SectionMutation.Insert(1))
        ))

        assertEquals(b, next.sectionIds[0])
        assertEquals(a, next.sectionIds[2])
        assertEquals(bItems, next.items(0, 2))
        assertTrue(next.sectionIds[1] !in setOf(a, b))
        assertTrue(next.itemId(1, 0) !in first.items(0, 1) + bItems)
    }

    @Test fun reloadKeepsEqualHeadersAndRegeneratesItems() {
        val tracker = SectionedStableIds()
        val first = tracker.apply(SectionedDelta(
            listOf(Section("Popular", listOf(1)), Section("More", listOf(2))),
            SectionedChange.Reload
        ))

        val next = tracker.apply(SectionedDelta(
            listOf(Section("More", listOf(2)), Section("New", emptyList<Int>()), Section("Popular", listOf(1))),
            SectionedChange.Reload
        ))

        assertEquals(listOf(first.sectionIds[1], first.sectionIds[0]), listOf(next.sectionIds[0], next.sectionIds[2]))
        assertTrue(next.sectionIds[1] !in first.sectionIds)
        assertNotEquals(first.itemId(1, 0), next.itemId(0, 0))
    }

    @Test fun inconsistentMutationsFallBackToReload() {
        val tracker = SectionedStableIds()
        val first = tracker.apply(SectionedDelta(listOf(Section("A", listOf(1))), SectionedChange.Reload))

        val next = tracker.apply(SectionedDelta(
            listOf(Section("A", listOf(1, 2, 3))),
            SectionedChange.Items(0, Mutation.Insert(1))
        ))

        assertEquals(first.sectionIds, next.sectionIds)
        assertEquals(3, next.items(0, 3).toSet().size)
        assertTrue(first.itemId(0, 0) !in next.items(0, 3))
    }

    @Test fun unloadedItemsGetIdsWithoutBeingRead() {
        val placeholder = object : AbstractSoftList<Int>() {
            override val size = 1_000
            override fun softGet(index: Int): SoftValue<Int>? = error("ids must not read items")
        }
        val ids = SectionedStableIds().apply(SectionedDelta(listOf(Section("Soft", placeholder)), SectionedChange.Reload))
        assertEquals(1_000, ids.items(0, 1_000).toSet().size)
    }

    @Test fun combinedSectionsKeepIdsAcrossChildMutations() = runTest {
        val popular = mutableDeltaListOf(listOf("a"))
        val more = mutableDeltaListOf(listOf("b"))
        val tracker = SectionedStableIds()
        val emitted = mutableListOf<SectionedIds>()
        val job = launch {
            sectionedDeltaList("Popular" to popular, "More" to more).collect { emitted += tracker.apply(it) }
        }
        runCurrent()
        val aId = emitted.last().itemId(0, 0)
        popular.insert(0, "z"); runCurrent()
        assertEquals(aId, emitted.last().itemId(0, 1))
        job.cancel()
    }
}

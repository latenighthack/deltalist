@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeliveryLifecycleTest {
    @Test fun lateAndPausedCollectorsReconstructIndependently() = runTest {
        val source = mutableDeltaListOf(listOf("A"))
        source.append("B")
        val slow = mutableListOf<Delta<String>>()
        val fast = mutableListOf<Delta<String>>()
        val gate = CompletableDeferred<Unit>()
        val a = launch { source.collect { slow += it; if (slow.size == 1) gate.await() } }
        val b = launch { source.collect { fast += it } }
        runCurrent()
        source.append("C"); runCurrent()
        source.removeAt(0); runCurrent()
        gate.complete(Unit); runCurrent()
        slow.assertFlatOracle(); fast.assertFlatOracle()
        assertIs<Change.Reload>(slow.last().change)
        assertIs<Change.Mutations>(fast.last().change)
        a.cancelAndJoin()
        source.move(0, 1); runCurrent()
        fast.assertFlatOracle()
        assertIs<Change.Reload>(source.first().change)
        assertEquals(2, slow.size)
        b.cancelAndJoin()
    }

    @Test fun sectionedBurstAndReloadReconstruct() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf(1))))
        source.appendItem(0, 2)
        val history = mutableListOf<SectionedDelta<String, Int>>()
        val gate = CompletableDeferred<Unit>()
        val job = launch { source.collect { history += it; if (history.size == 1) gate.await() } }
        runCurrent()
        source.appendSection("B", listOf(3))
        source.moveSection(0, 1)
        source.removeItem(1, 0)
        gate.complete(Unit); runCurrent()
        source.setItem(0, 0, 4); runCurrent()
        source.reload(listOf(Section("C", listOf(5)))); runCurrent()
        history.assertSectionedOracle()
        assertIs<SectionedChange.Reload>(history[1].change)
        assertIs<SectionedChange.Items>(history[2].change)
        job.cancelAndJoin()
    }

    @Test fun inputsAreCopiedAndItemsKeepIdentity() = runTest {
        val item = Any()
        val input = mutableListOf(item)
        val source = mutableDeltaListOf(input)
        input.clear()
        assertSame(item, source.value.single())
        source.reload(input)
        input.add(item)
        assertTrue(source.value.isEmpty())
        val nested = mutableListOf(item)
        val sections = mutableListOf(Section("A", nested))
        val sectioned = mutableSectionedDeltaListOf(sections)
        sections.clear()
        nested.clear()
        assertSame(item, sectioned.value.single().items.softLoadedItems().single())
        sectioned.reload(listOf(Section("B", nested)))
        nested.add(item)
        assertEquals(0, sectioned.value.single().items.size)
    }

    @Test fun paginationLateCollectorStartsWithReload() = runTest {
        val source = paginatedDeltaList<Int, Int>(scope = backgroundScope, startToken = 0) { _, token ->
            Page(listOf(token), afterToken = if (token == 0) 1 else null, beforeToken = null)
        }
        var latest: Delta<Int>? = null
        val job = launch { source.collect { latest = it } }
        runCurrent()
        (latest!!.items.softGet(latest!!.items.size - 1) as SoftValue.NotLoaded).request()
        runCurrent()
        assertIs<Change.Reload>(source.first().change)
        assertEquals(listOf(0, 1), source.first().items.softLoadedItems())
        job.cancelAndJoin()
    }

    @Test fun leasesSurviveMovesAndDoNotReleaseReplacementOrPeer() {
        val state = LazyMapState<Int, Any> { Any() }
        state.applyDelta(Delta(listOf(1, 2, 3), Change.Reload))
        val first = state.asList() as LeasedLazyList<Any>
        val a = first.acquireItem(0)!!
        val b = first.acquireItem(0)!!
        assertSame(a.item, b.item)
        state.applyDelta(Delta(listOf(2, 3, 1), Change.Mutations(Mutation.Move(0, 2))))
        a.release(); a.release()
        assertEquals(1, state.refCountOf(2))
        assertNull(first.acquireItem(0))
        b.release()
        assertEquals(0, state.refCountOf(2))
        val old = (state.asList() as LeasedLazyList<Any>).acquireItem(2)!!
        state.applyDelta(Delta(listOf(4, 5, 6), Change.Reload))
        val fresh = (state.asList() as LeasedLazyList<Any>).acquireItem(2)!!
        old.release()
        assertEquals(1, state.refCountOf(2))
        fresh.release()
        assertEquals(0, state.getCacheSize())
    }

    @Test fun groupingStopsAtGapAndNeverRequests() = runTest {
        var requests = 0
        fun snapshot(gap: Boolean) = object : AbstractSoftList<String>() {
            override val size = 3
            override fun softGet(index: Int): SoftValue<String>? = when (index) {
                0 -> SoftValue.Present("A")
                1 -> if (gap) SoftValue.NotLoaded { requests++ } else SoftValue.Present("A2")
                2 -> SoftValue.Present("B")
                else -> null
            }
        }
        val source = kotlinx.coroutines.flow.MutableStateFlow(Delta(snapshot(true), Change.Reload))
        val first = source.groupBy { it.first() }.first()
        assertEquals(listOf('A'), first.sections.map { it.header })
        source.value = Delta(snapshot(false), Change.Reload)
        val second = source.groupBy({ it.first() }, { key, items -> "$key:${items.size}" }).first()
        assertEquals(listOf("A:2", "B:1"), second.sections.map { it.header })
        assertEquals(0, requests)
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CompositionLifecycleTest {
    private class Tracking : AbstractSoftList<String>(), LeasedLazyList<String> {
        override val size = 3
        var requests = 0
        val pins = mutableMapOf<Int, Int>()
        override fun softGet(index: Int): SoftValue<String>? = when(index) {
            0 -> SoftValue.Present("A")
            1 -> SoftValue.NotLoaded { requests++ }
            2 -> SoftValue.Present("B")
            else -> null
        }
        override fun acquire(index: Int): SoftValue<String> {
            val value = softGet(index) ?: SoftValue.NotLoaded()
            if (value is SoftValue.Present) pins[index] = (pins[index] ?: 0) + 1
            return value
        }
        override fun acquireItem(index: Int): ItemLease<String>? =
            (acquire(index) as? SoftValue.Present)?.let { ItemLease(it.value) { release(index) } }
        override fun release(index: Int) { pins[index]?.let { if (it == 1) pins.remove(index) else pins[index] = it - 1 } }
        override fun releaseAll() { pins.clear() }
        override fun isAcquired(index: Int) = index in pins
    }

    @Test fun concatGuardsUnchangedChildAndLeasesOutliveWrapper() = runTest {
        val child = Tracking()
        val left = MutableStateFlow(Delta(child, Change.Reload))
        val right = mutableDeltaListOf(listOf("tail"))
        val flow = left.header("head").concat(right).footer("foot").withStableIds()
        var latest: Delta<StableItem<String>>? = null
        val job = launch { flow.collect { latest = it } }
        runCurrent()
        val old = latest!!.items as LeasedLazyList<StableItem<String>>
        val lease = old.acquireItem(1)!!
        val peer = old.acquireItem(1)!!
        assertEquals("A", lease.item.value)
        assertEquals(mapOf(0 to 2), child.pins)
        assertTrue(old.softGet(2) is SoftValue.NotLoaded)
        assertEquals(0, child.requests)
        right.append("tail2"); runCurrent()
        assertNull(old.acquireItem(1))
        old.releaseAll()
        (old.softGet(2) as SoftValue.NotLoaded).request()
        assertEquals(0, child.requests)
        assertEquals(mapOf(0 to 2), child.pins)
        lease.release(); lease.release()
        assertEquals(mapOf(0 to 1), child.pins)
        peer.release()
        assertTrue(child.pins.isEmpty())
        (latest!!.items.softGet(2) as SoftValue.NotLoaded).request()
        assertEquals(1, child.requests)
        job.cancelAndJoin()
    }

    @Test fun flattenAndMultiConcatRouteOnlyVisibleAcquisitions() = runTest {
        for (mapped in listOf(false, true)) {
            val child = Tracking()
            val source = MutableStateFlow(SectionedDelta(listOf(Section("header", child)), SectionedChange.Reload))
            val flat = if (mapped) source.flatten(header = { it }, item = { "row:$it" }, footer = { _, _ -> "footer" })
                else source.flattenItems()
            val flow = concatSections(listOf(flat, mutableDeltaListOf(listOf("tail"))))
            var latest: Delta<String>? = null
            val job = launch { flow.collect { latest = it } }
            runCurrent()
            val items = latest!!.items as LeasedLazyList<String>
            val offset = if (mapped) 1 else 0
            val lease = items.acquireItem(offset + 2)!!
            assertEquals(if (mapped) "row:B" else "B", lease.item)
            assertEquals(mapOf(2 to 1), child.pins)
            assertNull(items.acquireItem(offset + 1))
            assertNull(items.acquireItem(-1))
            assertNull(items.acquireItem(items.size))
            if (mapped) { items.acquireItem(0)!!.release(); items.acquireItem(4)!!.release() }
            assertEquals(0, child.requests)
            source.value = SectionedDelta(listOf(Section("new", listOf("X")), Section("header", child)), SectionedChange.Reload)
            runCurrent()
            lease.release()
            assertTrue(child.pins.isEmpty())
            assertNull(items.acquireItem(offset + 2))
            job.cancelAndJoin()
        }
    }

    @Test fun completedSourceKeepsFinalSnapshotAcquirable() = runTest {
        val final = flowOf(Delta(listOf("A").asSoftList(), Change.Reload))
            .lazyMap { Any() }.header(Any()).withStableIds().last()
        val items = final.items as LeasedLazyList<StableItem<Any>>
        val first = items.acquireItem(1)!!
        val second = items.acquireItem(1)!!
        assertSame(first.item.value, second.item.value)
        first.release()
        assertTrue(items.isAcquired(1))
        second.release()
        assertFalse(items.isAcquired(1))
    }

    @Test fun independentCollectorsAndCancellationDoNotEvictPeers() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3))
        val flow = source.lazyMap { Any() }.ifEmpty { Any() }.header(Any())
        var first: Delta<Any>? = null
        var second: Delta<Any>? = null
        val a = launch { flow.collect { first = it } }
        val b = launch { flow.collect { second = it } }
        runCurrent()
        val one = first!!.items.acquireItemOrGet(2)!!
        val two = second!!.items.acquireItemOrGet(2)!!
        assertNotSame(one.item, two.item)
        source.move(1, 0); runCurrent()
        val moved = second!!.items.acquireItemOrGet(1)!!
        assertSame(two.item, moved.item)
        a.cancelAndJoin()
        one.release()
        assertTrue((second!!.items as LazyList<*>).isAcquired(1))
        two.release(); moved.release()
        assertFalse((second!!.items as LazyList<*>).isAcquired(1))
        b.cancelAndJoin()
    }
}

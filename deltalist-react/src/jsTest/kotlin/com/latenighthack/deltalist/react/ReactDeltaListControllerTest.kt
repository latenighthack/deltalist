@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.mutableDeltaListOf
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.asSoftList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactDeltaListControllerTest {
    @Test fun realLazyMapReleasesAcrossMoveAndReplacement() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val controller = ReactDeltaListController { it }
        var latest: LazyList<*>? = null
        val job = backgroundScope.launch {
            source.lazyMap { Any() }.collect {
                latest = it.items as LazyList<*>
                controller.update(it.items)
            }
        }
        runCurrent()
        controller.proxy.visibleRange(0, 1)
        val first = controller.proxy[0]
        source.move(0, 1); runCurrent()
        assertSame(first, controller.proxy[1])
        controller.proxy.visibleRange(1, 1)
        assertTrue(!latest!!.isAcquired(0))
        controller.dispose()
        assertTrue(!latest!!.isAcquired(1))
        job.cancel()
    }

    @Test
    fun proxyIsStableArrayCompatibleAndDelegatesReads() {
        val controller = ReactDeltaListController { value -> "wrapped-$value" }
        val proxy = controller.proxy

        controller.update(listOf("a", "b").asSoftList())

        assertTrue(arrayIsArray(proxy))
        assertEquals(2, proxy.length)
        assertEquals(2, proxy.loadedCount)
        assertEquals("wrapped-a", proxy[0])
        assertEquals(listOf("wrapped-a", "wrapped-b"), arrayFrom(proxy).map { it as String })

        controller.update(listOf("c").asSoftList())
        assertSame(proxy, controller.proxy)
        assertEquals(2, proxy.revision)
        assertEquals("wrapped-c", proxy[0])
    }

    @Test
    fun unloadedSlotsAreHolesAndIterationDoesNotRequestThem() {
        val list = TrackingLazyList(mapOf(1 to "one", 3 to "three"), size = 4)
        val controller = ReactDeltaListController { it }
        val proxy = controller.proxy
        controller.update(list)

        assertEquals(4, proxy.totalSize)
        assertEquals(2, proxy.loadedCount)
        assertTrue(isUndefined(proxy[0]))
        assertEquals(listOf("one", "three"), arrayFrom(proxy).map { it as String })
        assertTrue(list.requested.isEmpty(), "ordinary iteration must not initiate pagination")
    }

    @Test
    fun mapSlotsRendersPlaceholdersThatAskWhileInView() {
        val original = js("globalThis.IntersectionObserver")
        val observers: dynamic = js("({ callback: null, observed: [] })")
        js("""globalThis.IntersectionObserver = function(callback) {
            observers.callback = callback;
            this.observe = function(element) { observers.observed.push(element); };
            this.unobserve = function() {};
            this.disconnect = function() {};
        }""")
        try {
            val first = TrackingLazyList(mapOf(0 to "zero"), size = 2)
            val controller = ReactDeltaListController { it }
            val proxy = controller.proxy
            controller.update(first)

            val refs = mutableMapOf<Int, dynamic>()
            val render: (dynamic, Int) -> dynamic = { value, _ -> "row-$value" }
            val placeholder: (Int, dynamic) -> dynamic = { index, ref ->
                refs[index] = ref
                "slot-$index"
            }
            val rendered = proxy.mapSlots(render, placeholder)
            assertEquals(listOf("row-zero", "slot-1"), arrayFrom(rendered).map { it as String })
            assertTrue(first.requested.isEmpty(), "rendering a placeholder doesn't ask for its slot")

            val element = js("document.createElement('div')")
            refs.getValue(1)(element)
            assertSame(element, observers.observed[0])
            assertTrue(first.requested.isEmpty(), "attaching doesn't ask until the slot is in view")

            observers.callback(js("[{ target: element, isIntersecting: true }]"))
            assertEquals(setOf(1), first.requested)

            // A change that leaves the slot unloaded while it's in view asks again.
            val second = TrackingLazyList(mapOf(0 to "zero"), size = 2)
            controller.update(second)
            assertEquals(setOf(1), second.requested)

            // Once the placeholder is gone, nothing asks.
            refs.getValue(1)(null)
            val third = TrackingLazyList(mapOf(0 to "zero"), size = 2)
            controller.update(third)
            assertTrue(third.requested.isEmpty())
            controller.dispose()
        } finally {
            js("globalThis.IntersectionObserver = original")
        }
    }

    @Test
    fun visibleRangeOwnsAcquireReleaseAndRequestsPlaceholders() {
        val list = TrackingLazyList(mapOf(1 to "one", 3 to "three"), size = 4)
        val controller = ReactDeltaListController { it }
        val proxy = controller.proxy
        controller.update(list)

        proxy.visibleRange(0, 1)
        assertEquals(setOf(0), list.requested)
        assertEquals(setOf(1), list.acquired)

        proxy.visibleRange(2, 3)
        assertEquals(setOf(0, 2), list.requested)
        assertEquals(setOf(3), list.acquired)
        assertEquals(listOf(1), list.released)

        controller.dispose()
        assertEquals(listOf(1, 3), list.released)
        assertTrue(list.acquired.isEmpty())
    }

    private class TrackingLazyList(
        private val values: Map<Int, String>,
        override val size: Int,
    ) : AbstractSoftList<String>(), LazyList<String> {
        val requested = mutableSetOf<Int>()
        val acquired = mutableSetOf<Int>()
        val released = mutableListOf<Int>()

        override fun softGet(index: Int): SoftValue<String>? = when {
            index !in 0 until size -> null
            index in values -> SoftValue.Present(values.getValue(index))
            else -> SoftValue.NotLoaded { requested += index }
        }

        override fun acquire(index: Int): SoftValue<String> {
            val value = softGet(index) ?: return SoftValue.NotLoaded()
            if (value is SoftValue.Present) acquired += index
            return value
        }

        override fun release(index: Int) {
            acquired -= index
            released += index
        }

        override fun releaseAll() {
            acquired.toList().forEach(::release)
        }

        override fun isAcquired(index: Int): Boolean = index in acquired
    }
}

private fun arrayIsArray(value: dynamic): Boolean = js("Array.isArray(value)")
private fun arrayFrom(value: dynamic): Array<dynamic> = js("Array.from(value)")
private fun isUndefined(value: dynamic): Boolean = js("value === undefined")

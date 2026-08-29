package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.asSoftList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactDeltaListControllerTest {
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

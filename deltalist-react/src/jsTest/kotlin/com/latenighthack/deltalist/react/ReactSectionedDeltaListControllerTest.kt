@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.lazyMap
import com.latenighthack.deltalist.operators.lazyMapItems
import com.latenighthack.deltalist.operators.sectionedDeltaList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReactSectionedDeltaListControllerTest {
    private fun ReactSectionedDeltaListController.bind(source: SectionedDeltaList<Any?, Any?>) =
        SectionedStableIds().let { ids -> source to { delta: SectionedDelta<Any?, Any?> -> update(delta, ids.apply(delta)) } }

    @Test fun sectionsKeepKeysAndItemProxiesAcrossMoves() = runTest {
        val list = mutableSectionedDeltaListOf<Any?, Any?>(listOf(
            Section("Popular", listOf("a", "b")),
            Section("More", listOf("c")),
        ))
        val controller = ReactSectionedDeltaListController({ "header-$it" }, { js("({ title: it })") })
        val (source, apply) = controller.bind(list)
        backgroundScope.launch { source.collect(apply) }
        runCurrent()

        val handle = controller.handle
        assertEquals(1, handle.revision)
        val popular = handle.sections[0]
        assertEquals("header-Popular", popular.header)
        val bKey = popular.items[1].key
        assertEquals(bKey, popular.items.keyAt(1))
        assertNotEquals(popular.key, handle.sections[1].key)

        list.moveSection(0, 1); runCurrent()
        assertEquals(popular.key, handle.sections[1].key)
        assertSame(popular.items, handle.sections[1].items)

        list.removeItem(1, 0); runCurrent()
        assertEquals(bKey, handle.sections[1].items[0].key)
        assertEquals(3, handle.revision)
        controller.dispose()
    }

    private class Row(val value: String)

    @Test fun lazilyMappedRowsKeepTheirObjectsWhenTheyShift() = runTest {
        var built = 0
        val list = mutableSectionedDeltaListOf<Any?, String>(listOf(Section("A", listOf("a1", "a2")), Section("B", listOf("b1"))))
        val rows = list.lazyMapItems<Any?, String, Any?> { Row(it).also { built++ } }
        val controller = ReactSectionedDeltaListController({ it }, { js("({ row: it })") })
        val ids = SectionedStableIds()
        backgroundScope.launch { rows.collect { controller.update(it, ids.apply(it)) } }
        runCurrent()

        val handle = controller.handle
        val first: dynamic = handle.sections[0].items
        val a1 = first[0].row
        val a2 = first[1].row
        val keys = listOf(first[0].key, first[1].key)
        assertEquals(2, built)

        list.insertItem(0, 0, "a0"); runCurrent()
        val shifted: dynamic = handle.sections[0].items
        assertSame(a1, shifted[1].row, "a1 after an insert above it")
        assertSame(a2, shifted[2].row, "a2 after an insert above it")
        assertEquals(keys, listOf(shifted[1].key, shifted[2].key))
        assertEquals(2, built, "the inserted row is built only when read")

        list.insertSection(0, "N", listOf("n")); runCurrent()
        val moved: dynamic = handle.sections[1].items
        assertSame(a1, moved[1].row, "a1 after a section insert")
        assertSame(a2, moved[2].row, "a2 after a section insert")
        assertEquals(keys, listOf(moved[1].key, moved[2].key))
        controller.dispose()
    }

    @Test fun visibleRangeSpansSectionsAndOwnsLeasesAndRequests() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val requested = mutableListOf<Int>()
        val soft = object : AbstractSoftList<Any?>() {
            override val size = 2
            override fun softGet(index: Int): SoftValue<Any?>? =
                if (index in 0 until size) SoftValue.NotLoaded { requested += index } else null
        }
        var lazy: LazyList<*>? = null
        val sections: SectionedDeltaList<Any?, Any?> = sectionedDeltaList(
            "Lazy" to source.lazyMap<String, Any?> { Any() },
            "Soft" to flowOf(Delta(soft, Change.Reload)),
        )
        val controller = ReactSectionedDeltaListController({ it }, { it })
        val ids = SectionedStableIds()
        backgroundScope.launch {
            sections.collect { delta ->
                lazy = delta.sections[0].items as LazyList<*>
                controller.update(delta, ids.apply(delta))
            }
        }
        runCurrent()

        val handle = controller.handle
        assertTrue(js("Array.from")(handle.sections[1].items).length == 0, "iteration must not request pages")
        assertTrue(requested.isEmpty())

        handle.visibleRange(0, 1, 1, 0)
        assertTrue(lazy!!.isAcquired(1))
        assertFalse(lazy!!.isAcquired(0))
        assertEquals(listOf(0), requested)

        handle.visibleRange(1, 1, 1, 1)
        assertFalse(lazy!!.isAcquired(1))
        assertEquals(listOf(0, 1), requested)

        handle.visibleRange(0, 0, 0, 0)
        assertTrue(lazy!!.isAcquired(0))
        controller.dispose()
        assertFalse(lazy!!.isAcquired(0))
    }

    @Test fun removedSectionsReleaseTheirLeases() = runTest {
        val source = mutableDeltaListOf(listOf("a"))
        val list = mutableSectionedDeltaListOf<Any?, Any?>()
        var lazy: LazyList<*>? = null
        backgroundScope.launch {
            source.lazyMap<String, Any?> { Any() }.collect { delta ->
                lazy = delta.items as LazyList<*>
                list.reload(listOf(Section("Lazy", delta.items)))
            }
        }
        val controller = ReactSectionedDeltaListController({ it }, { it })
        val ids = SectionedStableIds()
        backgroundScope.launch { list.collect { controller.update(it, ids.apply(it)) } }
        runCurrent()

        controller.handle.visibleRange(0, 0, 0, 0)
        assertTrue(lazy!!.isAcquired(0))
        list.removeSection(0); runCurrent()
        assertFalse(lazy!!.isAcquired(0))
        assertEquals(0, controller.handle.sections.length)
        controller.dispose()
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MutableListViewsTest {
    @Test fun iteratorAndNestedSublistEditsArePublished() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B", "C", "D"))
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { source.collect { history += it } }
        runCurrent()
        source.update { val iterator = it.iterator(); iterator.next(); iterator.remove() }
        runCurrent()
        source.update { it.subList(0, 3).subList(1, 2).clear() }
        runCurrent()
        assertEquals(listOf("B", "D"), source.value)
        assertEquals(3, history.size)
        var rendered = emptyList<String>()
        history.forEach { delta ->
            rendered = applyChange(rendered, delta)
            assertEquals(delta.items.softLoadedItems(), rendered)
        }
    }

    @Test fun listIteratorAddSetAndRemoveArePublished() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B"))
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { source.collect { history += it } }
        runCurrent()
        source.update { it.listIterator(1).add("X") }; runCurrent()
        source.update { val iterator = it.listIterator(); iterator.next(); iterator.set("Y") }; runCurrent()
        source.update { val iterator = it.listIterator(2); iterator.previous(); iterator.remove() }; runCurrent()
        assertEquals(listOf("Y", "B"), source.value)
        assertEquals(4, history.size)
        var rendered = emptyList<String>()
        history.forEach { delta ->
            rendered = applyChange(rendered, delta)
            assertEquals(delta.items.softLoadedItems(), rendered)
        }
    }

    @Test fun sectionUpdateTracksMutableViews() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("section", listOf(1, 2, 3))))
        source.updateSection(0) { it.subList(1, 3).clear() }
        assertEquals(listOf(1), source.value.single().items.softLoadedItems())
    }

    @Test fun retainAllUsesTheOriginalAliasedViewMembership() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 2, 4))
        val history = mutableListOf<Delta<Int>>()
        backgroundScope.launch { source.collect { history += it } }
        runCurrent()
        source.update { it.retainAll(it.subList(1, 4).subList(0, 2)) }
        runCurrent()
        assertEquals(listOf(2, 3, 2), source.value)
        assertEquals(2, history.size)
        assertEquals(source.value, applyChange(listOf(1, 2, 3, 2, 4), history.last()))
    }
}

package com.latenighthack.deltalist

import kotlin.test.Test
import kotlin.test.assertEquals

class MutableListRemoveAllTest {
    @Test fun removesEveryMatchingOccurrence() {
        val source = mutableDeltaListOf(listOf("A", "A", "B", "A", "C", "B"))
        source.update { it.removeAll(listOf("A", "B")) }
        assertEquals(listOf("C"), source.value)
    }

    @Test fun supportsAnAliasedRemovalCollection() {
        val source = mutableDeltaListOf(listOf(1, 2, 1, 3))
        source.update { it.removeAll(it.subList(0, 2)) }
        assertEquals(listOf(3), source.value)
    }

    @Test fun sectionUpdatesRemoveDuplicatesToo() {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf(1, 1, 2))))
        source.updateSection(0) { it.removeAll(listOf(1)) }
        assertEquals(listOf(2), source.value.single().items.softLoadedItems())
    }
}

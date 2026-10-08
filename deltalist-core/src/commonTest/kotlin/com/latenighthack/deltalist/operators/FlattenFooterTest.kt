@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlattenFooterTest {
    @Test fun footersFollowItemsAndHeaders() = verify(showHeaders = true)
    @Test fun footersFollowHiddenHeaders() = verify(showHeaders = false)

    private fun verify(showHeaders: Boolean) = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf(1, 2)), Section("B", listOf(3))))
        val deltas = collectDriven(source.flatten(
            header = if (showHeaders) { header -> "H:$header" } else null,
            item = { "I:$it" },
            footer = { header, items -> "F:$header:${items.joinToString()}" }
        )) {
            source.setItem(1, 0, 4); advanceUntilIdle()
            source.appendItem(0, 5); advanceUntilIdle()
            source.moveItem(0, 0, 2); advanceUntilIdle()
            source.removeItem(0, 1); advanceUntilIdle()
            source.updateSectionHeader(0, "renamed"); advanceUntilIdle()
        }
        deltas.assertFlatOracle()
        deltas.drop(1).forEach { assertIs<Change.Mutations>(it.change) }
        val expected = buildList {
            if (showHeaders) add("H:renamed")
            addAll(listOf("I:2", "I:1", "F:renamed:2, 1"))
            if (showHeaders) add("H:B")
            addAll(listOf("I:4", "F:B:4"))
        }
        assertEquals(expected, deltas.last().items.softLoadedItems())
    }
}

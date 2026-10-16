@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.filterItems
import com.latenighthack.deltalist.operators.sectionedDeltaList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A native binding pages a sectioned list by asking a section for the slot after its loaded rows. */
class SectionedPagingTest {
    @Test fun askingForTheSlotAfterASectionsRowsFetchesItsNextPage() = runTest {
        val tokens = mutableListOf<Int>()
        val pages = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            tokens += token
            Page(listOf(token * 2, token * 2 + 1), null, if (token < 2) token + 1 else null)
        }
        val sections = sectionedDeltaList(
            "even" to pages.filterItems { it % 2 == 0 },
            "odd" to pages.filterItems { it % 2 == 1 },
        )
        var latest: SectionedDelta<String, Int>? = null
        backgroundScope.launch { sections.collect { latest = it } }
        runCurrent()

        // Nothing loaded yet: asking a section for its first slot fetches the first page.
        latest!!.triggerLoadAt(sectionIndex = 0, itemIndex = 0)
        runCurrent()
        assertEquals(listOf(0), tokens)
        assertEquals(1, latest!!.getLoadedItemCountAt(0))

        // Asking for the slot after the loaded rows fetches the next page.
        latest!!.triggerLoadAt(sectionIndex = 1, itemIndex = latest!!.getLoadedItemCountAt(1))
        runCurrent()
        assertEquals(listOf(0, 1), tokens)
        assertEquals(2, latest!!.getLoadedItemCountAt(1))
    }

    @Test fun askingForALoadedOrAbsentSlotFetchesNothing() = runTest {
        val tokens = mutableListOf<Int>()
        val pages = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            tokens += token
            Page(listOf(token), null, null)
        }
        val sections = sectionedDeltaList("all" to pages)
        var latest: SectionedDelta<String, Int>? = null
        backgroundScope.launch { sections.collect { latest = it } }
        runCurrent()
        latest!!.triggerLoadAt(sectionIndex = 0, itemIndex = 0)
        runCurrent()
        assertEquals(listOf(0), tokens)
        latest!!.triggerLoadAt(sectionIndex = 0, itemIndex = 0)
        latest!!.triggerLoadAt(sectionIndex = 0, itemIndex = 5)
        latest!!.triggerLoadAt(sectionIndex = 3, itemIndex = 0)
        runCurrent()
        assertEquals(listOf(0), tokens)
    }
}

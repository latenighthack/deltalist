@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PaginationEmptyPageTest {
    @Test fun unchangedAfterPageKeepsThePublishedRequestLive() = runTest {
        val tokens = mutableListOf<Int>()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            tokens += token
            Page(if (token == 1) emptyList() else listOf(token), null, if (token < 2) token + 1 else null)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        val initial = latest!!
        val request = assertIs<SoftValue.NotLoaded>(initial.items.softGet(1))
        request.request(); runCurrent()
        assertSame(initial, latest)
        request.request(); runCurrent()
        assertEquals(listOf(0, 1, 2), tokens)
        assertEquals(listOf(0, 2), latest!!.items.softLoadedItems())
        request.request(); runCurrent()
        assertEquals(listOf(0, 1, 2), tokens)
    }

    @Test fun unchangedBeforePageKeepsThePublishedRequestLive() = runTest {
        val tokens = mutableListOf<Int>()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 2) { _, token ->
            tokens += token
            Page(if (token == 1) emptyList() else listOf(token), if (token > 0) token - 1 else null, null)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        val initial = latest!!
        val request = assertIs<SoftValue.NotLoaded>(initial.items.softGet(0))
        request.request(); runCurrent()
        assertSame(initial, latest)
        request.request(); runCurrent()
        assertEquals(listOf(2, 1, 0), tokens)
        assertEquals(listOf(0, 2), latest!!.items.softLoadedItems())
    }
}

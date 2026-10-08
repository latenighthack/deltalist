@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PaginationRequestTest {
    @Test fun queuedAfterRequestsCannotReuseAnOldToken() = runTest {
        val tokens = mutableListOf<Int>()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            tokens += token
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        val oldRequest = assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(1))
        repeat(10) { oldRequest.request() }
        runCurrent()
        assertEquals(listOf(0, 1), tokens)
        oldRequest.request(); runCurrent()
        assertEquals(listOf(0, 1), tokens)
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(2)).request(); runCurrent()
        assertEquals(listOf(0, 1, 2), tokens)
        assertEquals(listOf(0, 1, 2), latest!!.items.softLoadedItems())
    }

    @Test fun queuedBeforeRequestsCannotReuseAnOldToken() = runTest {
        val tokens = mutableListOf<Int>()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 2) { _, token ->
            tokens += token
            Page(listOf(token), beforeToken = if (token > 0) token - 1 else null, afterToken = null)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        val oldRequest = assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(0))
        repeat(10) { oldRequest.request() }
        runCurrent()
        assertEquals(listOf(2, 1), tokens)
        oldRequest.request(); runCurrent()
        assertEquals(listOf(2, 1), tokens)
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(0)).request(); runCurrent()
        assertEquals(listOf(2, 1, 0), tokens)
        assertEquals(listOf(0, 1, 2), latest!!.items.softLoadedItems())
    }
}

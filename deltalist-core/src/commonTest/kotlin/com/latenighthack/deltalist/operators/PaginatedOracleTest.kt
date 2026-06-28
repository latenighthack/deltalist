package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.paginatedDeltaList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Placeholder-aware oracle sweep over [paginatedDeltaList]. Drives fetches in both directions by
 * requesting leading/trailing placeholders and asserts every emission reconstructs the previous
 * one via [assertSoftOracle] (which models [SoftValue.NotLoaded] slots, not just loaded items).
 */
class PaginatedOracleTest {

    private data class Item(val id: Int)

    @Test
    fun bidirectionalFetchesAreSoftOracleConsistent() = runTest {
        // Five chained pages, start in the middle so both directions are exercised.
        val pages = mapOf<Int, Page<Item, Int>>(
            0 to Page(listOf(Item(0), Item(1)), beforeToken = null, afterToken = 1),
            1 to Page(listOf(Item(2), Item(3)), beforeToken = 0, afterToken = 2),
            2 to Page(listOf(Item(4), Item(5)), beforeToken = 1, afterToken = 3),
            3 to Page(listOf(Item(6), Item(7)), beforeToken = 2, afterToken = 4),
            4 to Page(listOf(Item(8), Item(9)), beforeToken = 3, afterToken = null)
        )

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 2,
            fetchWindowSize = 1,
            fetch = { _: LoadDirection, token: Int ->
                pages[token] ?: throw AssertionError("unexpected token $token")
            }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch { flow.collect { results.add(it) } }
        delay(100)

        // Alternately request the leading and trailing placeholders until both are exhausted.
        repeat(6) {
            val items = results.last().items
            (items.softGet(0) as? SoftValue.NotLoaded)?.request()
            delay(50)
            val tail = results.last().items
            (tail.softGet(tail.size - 1) as? SoftValue.NotLoaded)?.request()
            delay(50)
        }
        job.cancel()

        results.assertSoftOracle()

        // After exhausting both directions every slot must be loaded and in id order.
        val finalItems = results.last().items
        assertTrue((0 until finalItems.size).all { finalItems.softGet(it) is SoftValue.Present })
    }

    @Test
    fun bottomAnchoredWithEstimateIsSoftOracleConsistent() = runTest {
        // Load the LAST page first with an estimate, so the leading region inflates to per-item
        // placeholders. Scrolling up (requesting the leading placeholder) must convert them to
        // present items in place, each emission reconstructing the previous via the soft oracle.
        val pages = mapOf<Int, Page<Item, Int>>(
            0 to Page(listOf(Item(0), Item(1)), beforeToken = null, afterToken = null, estimatedTotalSize = 10),
            1 to Page(listOf(Item(2), Item(3)), beforeToken = 0, afterToken = null, estimatedTotalSize = 10),
            2 to Page(listOf(Item(4), Item(5)), beforeToken = 1, afterToken = null, estimatedTotalSize = 10),
            3 to Page(listOf(Item(6), Item(7)), beforeToken = 2, afterToken = null, estimatedTotalSize = 10),
            4 to Page(listOf(Item(8), Item(9)), beforeToken = 3, afterToken = null, estimatedTotalSize = 10)
        )

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 4,
            fetchWindowSize = 1,
            fetch = { _: LoadDirection, token: Int -> pages[token] ?: throw AssertionError("unexpected token $token") }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch { flow.collect { results.add(it) } }
        delay(100)

        // The initial display is 10 rows (8 leading placeholders + the last page 8,9).
        assertTrue(results.last().items.size == 10)
        assertTrue(results.last().items.softGet(0) is SoftValue.NotLoaded)
        assertTrue(results.last().items.softGet(9) is SoftValue.Present)

        // Request the leading placeholder repeatedly to page upward to the top.
        repeat(6) {
            val items = results.last().items
            (items.softGet(0) as? SoftValue.NotLoaded)?.request()
            delay(50)
        }
        job.cancel()

        results.assertSoftOracle()

        val finalItems = results.last().items
        assertTrue((0 until finalItems.size).all { finalItems.softGet(it) is SoftValue.Present })
        assertTrue(finalItems.size == 10)
    }

    @Test
    fun appendOnlyPaginationIsSoftOracleConsistent() = runTest {
        val pages = mapOf<Int, Page<Item, Int>>(
            0 to Page(listOf(Item(0), Item(1)), beforeToken = null, afterToken = 1),
            1 to Page(listOf(Item(2), Item(3)), beforeToken = null, afterToken = 2),
            2 to Page(listOf(Item(4), Item(5)), beforeToken = null, afterToken = null)
        )

        val flow = paginatedDeltaList<Item, Int>(
            scope = this,
            startToken = 0,
            fetchWindowSize = 1,
            fetch = { _: LoadDirection, token: Int -> pages[token]!! }
        )

        val results = mutableListOf<Delta<Item>>()
        val job = launch { flow.collect { results.add(it) } }
        delay(100)

        repeat(4) {
            val items = results.last().items
            (items.softGet(items.size - 1) as? SoftValue.NotLoaded)?.request()
            delay(50)
        }
        job.cancel()

        results.assertSoftOracle()
    }
}

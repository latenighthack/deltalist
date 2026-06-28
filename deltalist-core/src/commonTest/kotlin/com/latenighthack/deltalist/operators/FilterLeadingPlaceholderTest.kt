package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.paginatedDeltaList
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the filter operators against a *bottom-anchored* paginated source — one that loads the
 * last page first and prepends older pages via BEFORE fetches. With an estimate, the leading
 * (load-earlier) region inflates to per-item placeholders, symmetric to the trailing side of the
 * ordinary (top-anchored) paginated demo. The filter must surface those leading placeholders
 * (ratio-estimated) so the present items stay anchored at the bottom and paging up converts
 * placeholders to present items without the list size collapsing.
 */
class FilterLeadingPlaceholderTest {

    // total=100, pageSize=10, last page token = 9. Bottom-anchored: start at the last page,
    // afterToken always null (nothing below), beforeToken walks back toward page 0.
    private fun bottomAnchored(
        scope: kotlinx.coroutines.CoroutineScope,
        onDirection: (LoadDirection) -> Unit = {}
    ) = paginatedDeltaList<Int, Int>(
        scope = scope,
        fetchWindowSize = 2,
        startToken = 9
    ) { direction, token ->
        onDirection(direction)
        val start = token * 10
        Page(
            items = (start until start + 10).toList(),
            beforeToken = if (token > 0) token - 1 else null,
            afterToken = null,
            estimatedTotalSize = 100
        )
    }

    /** Loaded (Present) values in index order, regardless of where placeholders sit. */
    private fun Delta<Int>.presentValues(): List<Int> =
        (0 until items.size).mapNotNull { (items.softGetOrNull(it) as? SoftValue.Present)?.value }

    @Test
    fun bottomAnchored_withFilter_preLoadShowsSkeletonColumn() = runTest {
        // A pending first fetch with an initial estimate: the filtered snapshot must surface the
        // whole estimated column as skeleton rows, not collapse to a single placeholder.
        val source = paginatedDeltaList<Int, Int>(
            scope = this,
            startToken = 9,
            initialEstimatedSize = 100
        ) { _, token ->
            delay(1000)
            val start = token * 10
            Page(
                items = (start until start + 10).toList(),
                beforeToken = if (token > 0) token - 1 else null,
                afterToken = null,
                estimatedTotalSize = 100
            )
        }
        val predicate = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }
        val filtered = source.filterItemsDynamic(predicate)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }
        delay(100)

        val preload = results.last()
        assertEquals(100, preload.items.size)
        assertTrue((0 until preload.items.size).all { preload.items.softGetOrNull(it) is SoftValue.NotLoaded })

        job.cancel()
    }

    @Test
    fun bottomAnchored_noFilter_leadingPlaceholdersInflated_presentAtBottom() = runTest {
        val predicate = MutableStateFlow<(Int) -> Boolean> { true }
        val filtered = bottomAnchored(this).filterItemsDynamic(predicate)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }
        delay(100)

        val delta = results.last()
        // 90 leading placeholders + the last page (90..99) present at the bottom, no trailing slot.
        assertEquals(100, delta.items.size)
        assertIs<SoftValue.NotLoaded>(delta.items.softGetOrNull(0))
        assertIs<SoftValue.NotLoaded>(delta.items.softGetOrNull(89))
        assertEquals(99, assertIs<SoftValue.Present<Int>>(delta.items.softGetOrNull(99)).value)
        assertEquals((90..99).toList(), delta.presentValues())

        job.cancel()
    }

    @Test
    fun bottomAnchored_noFilter_requestLeading_loadsOlderPageInPlace() = runTest {
        val directions = mutableListOf<LoadDirection>()
        val predicate = MutableStateFlow<(Int) -> Boolean> { true }
        val filtered = bottomAnchored(this) { directions.add(it) }.filterItemsDynamic(predicate)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }
        delay(100)

        // Access a leading "load earlier" placeholder — this must drive a BEFORE fetch.
        (results.last().items.softGetOrNull(0) as SoftValue.NotLoaded).request()
        delay(100)

        assertTrue(LoadDirection.BEFORE in directions, "leading placeholder should trigger a BEFORE fetch")

        val delta = results.last()
        // Size unchanged (placeholders converted in place); older page (80..89) now present too.
        assertEquals(100, delta.items.size)
        assertIs<SoftValue.NotLoaded>(delta.items.softGetOrNull(0))
        assertEquals((80..99).toList(), delta.presentValues())

        job.cancel()
    }

    @Test
    fun bottomAnchored_withFilter_ratioEstimatesLeading_presentAtBottom() = runTest {
        val predicate = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }
        val filtered = bottomAnchored(this).filterItemsDynamic(predicate)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { filtered.collect { results.add(it) } }
        delay(100)

        val delta = results.last()
        // Ratio 50%: estimate 100 -> 50 filtered rows = 45 leading placeholders + 5 present evens.
        assertEquals(50, delta.items.size)
        assertIs<SoftValue.NotLoaded>(delta.items.softGetOrNull(0))
        assertEquals(listOf(90, 92, 94, 96, 98), delta.presentValues())

        // Paging up loads the older page through the filter; size stays stable (no collapse).
        (delta.items.softGetOrNull(0) as SoftValue.NotLoaded).request()
        delay(100)

        val after = results.last()
        assertEquals(50, after.items.size)
        assertEquals((80..99).filter { it % 2 == 0 }, after.presentValues())
        assertIs<SoftValue.NotLoaded>(after.items.softGetOrNull(0))

        job.cancel()
    }
}

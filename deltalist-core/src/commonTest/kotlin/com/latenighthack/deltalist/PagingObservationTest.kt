@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PagingObservationTest {

    private class Recorder : PagingObserver {
        val events = mutableListOf<PagingEvent>()
        override fun onPaging(event: PagingEvent) { events += event }
        /** Events with fetch ids and durations stripped, for exact comparison. */
        fun shapes(): List<String> = events.map {
            when (it) {
                is PagingEvent.Requested -> "Requested(${it.direction}, ${it.placeholderRange})"
                is PagingEvent.Started -> "Started(${it.direction})"
                is PagingEvent.Completed -> "Completed(${it.direction}, ${it.itemCount})"
                is PagingEvent.Failed -> "Failed(${it.direction}, ${it.error.message})"
                is PagingEvent.Cancelled -> "Cancelled(${it.direction})"
            }
        }
    }

    private fun fetchIds(events: List<PagingEvent>): List<Long> = events.mapNotNull {
        when (it) {
            is PagingEvent.Started -> it.fetchId
            is PagingEvent.Completed -> it.fetchId
            is PagingEvent.Failed -> it.fetchId
            is PagingEvent.Cancelled -> it.fetchId
            is PagingEvent.Requested -> null
        }
    }

    @Test fun initialRequestStartAndComplete() = runTest {
        val recorder = Recorder()
        val source = paginatedDeltaList<Int, Int>(
            backgroundScope, startToken = 0, initialEstimatedSize = 5, observer = recorder
        ) { _, _ -> Page(listOf(1, 2), beforeToken = null, afterToken = null) }
        backgroundScope.launch { source.collect { } }
        runCurrent()
        assertEquals(
            listOf("Requested(INITIAL, 0..4)", "Started(INITIAL)", "Completed(INITIAL, 2)"),
            recorder.shapes()
        )
        val ids = fetchIds(recorder.events)
        assertEquals(1, ids.toSet().size)
        assertTrue((recorder.events[2] as PagingEvent.Completed).duration >= kotlin.time.Duration.ZERO)
    }

    @Test fun afterDemandReportsTrailingRange() = runTest {
        val recorder = Recorder()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = recorder) { _, token ->
            Page(listOf(token * 10, token * 10 + 1), beforeToken = null, afterToken = token + 1, estimatedTotalSize = 6)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        recorder.events.clear()
        // 2 loaded + 4 trailing placeholders: indices 2..5.
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(3)).request()
        runCurrent()
        assertEquals(listOf("Requested(AFTER, 2..5)", "Started(AFTER)", "Completed(AFTER, 2)"), recorder.shapes())
        assertEquals(listOf(0, 1, 10, 11), latest!!.items.softLoadedItems())
    }

    @Test fun beforeDemandReportsLeadingRange() = runTest {
        val recorder = Recorder()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 2, observer = recorder) { _, token ->
            Page(listOf(token), beforeToken = if (token > 0) token - 1 else null, afterToken = null, estimatedTotalSize = 3)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        recorder.events.clear()
        // Anchored at the bottom with estimate 3: 2 leading placeholders then item 2.
        assertEquals(3, latest!!.items.size)
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(0)).request()
        runCurrent()
        assertEquals(listOf("Requested(BEFORE, 0..1)", "Started(BEFORE)", "Completed(BEFORE, 1)"), recorder.shapes())
        // One leading placeholder remains in front of the loaded items.
        val present = (0 until latest!!.items.size).mapNotNull { (latest!!.items.softGet(it) as? SoftValue.Present)?.value }
        assertEquals(listOf(1, 2), present)
    }

    @Test fun requestWhileFetchInFlightIsRequestedWithoutStarted() = runTest {
        val recorder = Recorder()
        val gate = CompletableDeferred<Unit>()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = recorder) { direction, token ->
            if (direction == LoadDirection.AFTER) gate.await()
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        recorder.events.clear()
        val placeholder = assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(1))
        placeholder.request(); runCurrent()
        placeholder.request(); runCurrent()
        assertEquals(
            listOf("Requested(AFTER, 1..1)", "Started(AFTER)", "Requested(AFTER, 1..1)"),
            recorder.shapes()
        )
        gate.complete(Unit); runCurrent()
        assertEquals("Completed(AFTER, 1)", recorder.shapes().last())
        assertEquals(1, recorder.events.count { it is PagingEvent.Started })
    }

    @Test fun failedFetchIsReportedAndStillRethrown() = runTest {
        val recorder = Recorder()
        val thrown = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e -> thrown += e }
        )
        val source = paginatedDeltaList<Int, Int>(scope, startToken = 0, observer = recorder) { _, _ ->
            throw IllegalStateException("boom")
        }
        backgroundScope.launch { source.collect { } }
        runCurrent()
        assertEquals(listOf("Requested(INITIAL, 0..0)", "Started(INITIAL)", "Failed(INITIAL, boom)"), recorder.shapes())
        assertEquals(listOf("boom"), thrown.map { it.message })
        scope.cancel()
    }

    @Test fun scopeCancelledMidFetchIsCancelled() = runTest {
        val recorder = Recorder()
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val neverCompletes = CompletableDeferred<Unit>()
        val source = paginatedDeltaList<Int, Int>(scope, startToken = 0, observer = recorder) { _, _ ->
            neverCompletes.await()
            Page(emptyList(), beforeToken = null, afterToken = null)
        }
        backgroundScope.launch { source.collect { } }
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals(listOf("Requested(INITIAL, 0..0)", "Started(INITIAL)", "Cancelled(INITIAL)"), recorder.shapes())
    }

    @Test fun cancellationFromFetchStillPropagatesAsCancellation() = runTest {
        val recorder = Recorder()
        val thrown = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e -> thrown += e }
        )
        val source = paginatedDeltaList<Int, Int>(scope, startToken = 0, observer = recorder) { _, _ ->
            throw CancellationException("stop")
        }
        backgroundScope.launch { source.collect { } }
        runCurrent()
        assertEquals("Cancelled(INITIAL)", recorder.shapes().last())
        // A CancellationException is not delivered to the exception handler.
        assertTrue(thrown.isEmpty())
        scope.cancel()
    }

    @Test fun throwingObserverIsSwallowedAndPagingContinues() = runTest {
        val calls = mutableListOf<PagingEvent>()
        val throwing = PagingObserver { calls += it; throw IllegalStateException("observer") }
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = throwing) { _, token ->
            Page(listOf(token), beforeToken = null, afterToken = if (token < 1) token + 1 else null)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(1)).request()
        runCurrent()
        assertEquals(listOf(0, 1), latest!!.items.softLoadedItems())
        assertEquals(6, calls.size)
    }

    @Test fun collectorContextObservationIsRegisteredOnlyWhileCollecting() = runTest {
        val recorder = Recorder()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        // A plain collector keeps the list's snapshots visible to the test.
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        assertTrue(recorder.events.isEmpty())

        val observed = backgroundScope.launch { source.flowOn(PagingObservation(recorder)).collect { } }
        runCurrent()
        // The initial load was already done: only the collection-start request is reported.
        assertEquals(listOf("Requested(INITIAL, 0..0)"), recorder.shapes())

        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(1)).request()
        runCurrent()
        assertEquals(
            listOf("Requested(INITIAL, 0..0)", "Requested(AFTER, 1..1)", "Started(AFTER)", "Completed(AFTER, 1)"),
            recorder.shapes()
        )

        observed.cancel()
        runCurrent()
        recorder.events.clear()
        assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(2)).request()
        runCurrent()
        assertTrue(recorder.events.isEmpty())
        assertEquals(listOf(0, 1, 2), latest!!.items.softLoadedItems())
    }

    @Test fun explicitAndCollectorObserversBothReceiveEvents() = runTest {
        val explicit = Recorder()
        val collector = Recorder()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = explicit) { _, _ ->
            Page(listOf(1), beforeToken = null, afterToken = null)
        }
        backgroundScope.launch { source.flowOn(PagingObservation(collector)).collect { } }
        runCurrent()
        val expected = listOf("Requested(INITIAL, 0..0)", "Started(INITIAL)", "Completed(INITIAL, 1)")
        assertEquals(expected, explicit.shapes())
        assertEquals(expected, collector.shapes())
        assertEquals(explicit.events, collector.events)
    }

    @Test fun fetchIdsAreUniqueAcrossFetches() = runTest {
        val recorder = Recorder()
        val source = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = recorder) { _, token ->
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        var latest: Delta<Int>? = null
        backgroundScope.launch { source.collect { latest = it } }
        runCurrent()
        repeat(3) { i ->
            assertIs<SoftValue.NotLoaded>(latest!!.items.softGet(i + 1)).request()
            runCurrent()
        }
        val started = recorder.events.filterIsInstance<PagingEvent.Started>().map { it.fetchId }
        assertEquals(4, started.size)
        assertEquals(4, started.toSet().size)
    }

    @Test fun noObserverLeavesBehaviourUnchanged() = runTest {
        val tokens = mutableListOf<Int>()
        val nullObserver = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0, observer = null) { _, token ->
            tokens += token
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        val plain = paginatedDeltaList<Int, Int>(backgroundScope, startToken = 0) { _, token ->
            Page(listOf(token), beforeToken = null, afterToken = token + 1)
        }
        val a = mutableListOf<Delta<Int>>()
        val b = mutableListOf<Delta<Int>>()
        backgroundScope.launch { nullObserver.collect { a += it } }
        backgroundScope.launch { plain.collect { b += it } }
        runCurrent()
        assertIs<SoftValue.NotLoaded>(a.last().items.softGet(1)).request()
        assertIs<SoftValue.NotLoaded>(b.last().items.softGet(1)).request()
        runCurrent()
        assertEquals(listOf(0, 1), tokens)
        assertEquals(b.map { it.change }, a.map { it.change })
        assertEquals(b.map { it.items.softLoadedItems() }, a.map { it.items.softLoadedItems() })
        assertEquals(b.map { it.items.size }, a.map { it.items.size })
    }
}

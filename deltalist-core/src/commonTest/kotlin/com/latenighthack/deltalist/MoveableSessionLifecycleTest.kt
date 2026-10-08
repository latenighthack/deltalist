@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.assertFlatOracle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

class MoveableSessionLifecycleTest {
    @Test fun sharedSessionSurvivesLeaderCancellationAndRestartsAfterLastSubscriber() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2))
        var activeUpstreams = 0
        var upstreamStarts = 0
        val upstream = flow {
            activeUpstreams++
            upstreamStarts++
            try {
                source.collect { emit(it) }
            } finally {
                activeUpstreams--
            }
        }
        val moving = upstream.moveable { _, _, _ -> true }
        val first = mutableListOf<Delta<Int>>()
        val second = mutableListOf<Delta<Int>>()
        val leader = backgroundScope.launch { moving.collect { first += it } }
        runCurrent()
        val peer = backgroundScope.launch { moving.collect { second += it } }
        runCurrent()
        assertEquals(1, upstreamStarts)
        assertEquals(1, activeUpstreams)
        assertTrue(moving.beginDrag(0))
        moving.updateDragPreview(1)
        runCurrent()
        assertEquals(listOf(2, 1), first.last().items.softLoadedItems())
        assertEquals(listOf(2, 1), second.last().items.softLoadedItems())

        leader.cancelAndJoin()
        runCurrent()
        assertEquals(1, activeUpstreams)
        assertTrue(peer.isActive)
        assertIs<DragState.Dragging<Int>>(moving.dragState.value)
        source.append(3)
        runCurrent()
        moving.cancelDrag(); runCurrent()
        assertEquals(listOf(1, 2, 3), second.last().items.softLoadedItems())
        first.assertFlatOracle()
        second.assertFlatOracle()

        peer.cancelAndJoin()
        runCurrent()
        assertEquals(0, activeUpstreams)
        source.append(4)
        val restarted = mutableListOf<Delta<Int>>()
        val next = backgroundScope.launch { moving.collect { restarted += it } }
        runCurrent()
        assertEquals(2, upstreamStarts)
        assertEquals(1, activeUpstreams)
        assertEquals(listOf(1, 2, 3, 4), restarted.last().items.softLoadedItems())
        assertIs<Change.Reload>(restarted.first().change)
        restarted.assertFlatOracle()
        next.cancelAndJoin()
        runCurrent()
        assertEquals(0, activeUpstreams)
    }

    @Test fun finiteUpstreamKeepsItsFinalSnapshotAvailableForPreviews() = runTest {
        var activeUpstreams = 0
        val moving = flow {
            activeUpstreams++
            try {
                emit(Delta(listOf(1), Change.Reload))
                yield()
                emit(Delta(listOf(1, 2), Change.Mutations(Mutation.Insert(1))))
            } finally {
                activeUpstreams--
            }
        }.moveable { _, _, _ -> true }
        val history = mutableListOf<Delta<Int>>()
        val subscriber = backgroundScope.launch { moving.collect { history += it } }
        runCurrent()
        assertEquals(0, activeUpstreams)
        assertTrue(subscriber.isActive)
        assertEquals(listOf(1, 2), history.last().items.softLoadedItems())
        assertTrue(moving.beginDrag(0))
        moving.updateDragPreview(1)
        runCurrent()
        assertEquals(listOf(2, 1), history.last().items.softLoadedItems())
        history.assertFlatOracle()
        subscriber.cancelAndJoin()
        assertEquals(0, activeUpstreams)
    }

    @Test fun upstreamFailureTerminatesEverySubscriberAndReleasesTheSession() = runTest {
        val failure = IllegalStateException("upstream failed")
        val fail = CompletableDeferred<Unit>()
        var activeUpstreams = 0
        val moving = flow {
            activeUpstreams++
            try {
                emit(Delta(listOf(1), Change.Reload))
                fail.await()
                throw failure
            } finally {
                activeUpstreams--
            }
        }.moveable { _, _, _ -> true }
        val failures = mutableListOf<Throwable>()
        val jobs = List(2) {
            backgroundScope.launch {
                try {
                    moving.collect { }
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        runCurrent()
        assertEquals(1, activeUpstreams)
        fail.complete(Unit)
        runCurrent()
        assertTrue(jobs.all { it.isCompleted })
        assertEquals(0, activeUpstreams)
        assertEquals(2, failures.size)
        failures.forEach { assertSame(failure, it) }
    }
}

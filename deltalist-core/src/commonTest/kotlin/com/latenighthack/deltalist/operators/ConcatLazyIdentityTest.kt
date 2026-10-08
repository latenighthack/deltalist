@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConcatLazyIdentityTest {
    @Test fun binaryConcatPreservesLazyIdentity() = verifyLazyIdentity(multiple = false)
    @Test fun multiConcatPreservesLazyIdentity() = verifyLazyIdentity(multiple = true)

    private fun verifyLazyIdentity(multiple: Boolean) = runTest {
        var transformed = 0
        val source = mutableDeltaListOf(listOf(1, 2, 3))
        val rows = source.lazyMap { transformed++; Any() }
        val header = flowOf(Delta(listOf(Any()), Change.Reload))
        val joined = if (multiple) concatSections(header, rows) else header.concat(rows)
        var current: Delta<StableItem<Any>>? = null
        val job = launch { joined.withStableIds().collect { current = it } }
        runCurrent()
        assertEquals(0, transformed, "composition must not construct offscreen rows")
        val initial = current!!
        val oldLease = initial.items.acquireItemOrGet(1)!!
        assertEquals(1, transformed)

        source.append(4)
        runCurrent()
        val next = current!!
        assertIs<Change.Mutations>(next.change)
        assertEquals(1, transformed, "an append must not materialize retained or new rows")
        val nextLease = next.items.acquireItemOrGet(1)!!
        assertEquals(oldLease.item.stableId, nextLease.item.stableId)
        assertSame(oldLease.item.value, nextLease.item.value)
        assertEquals(1, transformed)
        oldLease.release()
        assertTrue((next.items as LazyList<*>).isAcquired(1))
        nextLease.release()
        assertFalse((next.items as LazyList<*>).isAcquired(1))
        job.cancelAndJoin()
    }

    @Test fun concatDoesNotInspectSoftSlots() = runTest {
        val soft = object : AbstractSoftList<Int>() {
            override val size = 100
            override fun softGet(index: Int): SoftValue<Int>? = error("composition inspected slot $index")
        }
        val source = flowOf(Delta(soft, Change.Reload))
        val tail = flowOf(Delta(listOf(101), Change.Reload))
        assertEquals(101, source.concat(tail).last().items.size)
        assertEquals(101, concatSections(source, tail).last().items.size)
    }

    @Test fun binaryConcatReloadsAfterSkippedEmissions() = verifyBackpressure(multiple = false)
    @Test fun multiConcatReloadsAfterSkippedEmissions() = verifyBackpressure(multiple = true)

    private fun verifyBackpressure(multiple: Boolean) = runTest {
        val begin = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val source = flow {
            emit(Delta(listOf(1), Change.Reload))
            begin.await()
            for (size in 2..4) emit(Delta((1..size).toList(), Change.Mutations(Mutation.Insert(size - 1))))
        }
        val header = flowOf(Delta(listOf(0), Change.Reload))
        val joined = if (multiple) concatSections(header, source) else header.concat(source)
        val deltas = mutableListOf<Delta<Int>>()
        val job = launch {
            joined.collect {
                deltas += it
                if (deltas.size == 1) {
                    begin.complete(Unit)
                    unblock.await()
                }
            }
        }
        runCurrent()
        unblock.complete(Unit)
        runCurrent()
        job.cancelAndJoin()
        deltas.assertFlatOracle()
        assertEquals(listOf(0, 1, 2, 3, 4), deltas.last().items.softLoadedItems())
        assertTrue(deltas.drop(1).any { it.change is Change.Reload })
    }
}

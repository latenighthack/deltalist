@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.LeasedLazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionMutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedStableIds
import com.latenighthack.deltalist.acquireItemAt
import com.latenighthack.deltalist.getItemAt
import com.latenighthack.deltalist.getLoadedItemCountAt
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.softLoadedItems
import com.latenighthack.deltalist.trackStableIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SectionedLazyMapTest {

    /** A row built by the mapper; identity tells whether the cache served it. */
    private data class RowModel(val value: String)

    private var built = 0
    private fun row(value: String) = RowModel(value).also { built++ }

    private fun <S, R> SectionedDelta<S, R>.lease(section: Int, item: Int): ItemLease<R> =
        (sections[section].items as LeasedLazyList<R>).acquireItem(item)!!

    private fun <S> SectionedDelta<S, RowModel>.peekValues() =
        sections.map { section -> section.items.softLoadedItems().map { it.value } }

    @Test
    fun eachSectionIsALazyListAndOnlyAcquiredRowsAreBuilt() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1", "a2")), Section("B", listOf("b1"))))
        val deltas = collectWith(source.lazyMapItems(::row)) { }

        val delta = deltas.single()
        assertTrue(delta.sections.all { it.items is LazyList<*> })
        assertEquals(0, built)
        val lease = delta.lease(1, 0)
        assertEquals("b1", lease.item.value)
        assertEquals(1, built)
        assertSame(lease.item, delta.lease(1, 0).item)
        assertEquals(1, built)
    }

    @Test
    fun cachedRowsFollowTheirItemsInsideASection() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1", "a2", "a3"))))
        lateinit var rows: List<RowModel>
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            rows = (0 until 3).map { emitted.last().lease(0, it).item }
            source.insertItem(0, 0, "a0")
            advanceUntilIdle()
            source.moveItem(0, 3, 1)
            advanceUntilIdle()
            source.removeItem(0, 2)
            advanceUntilIdle()
        }

        val last = deltas.last()
        val builtBefore = built
        assertSame(rows[2], last.lease(0, 1).item)
        assertSame(rows[1], last.lease(0, 2).item)
        assertEquals(builtBefore, built)
        deltas.assertSectionedOracle()
        assertEquals(listOf(listOf("a0", "a3", "a2")), last.peekValues())
    }

    @Test
    fun cachedRowsFollowTheirSectionsThroughInsertRemoveAndMove() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a")), Section("B", listOf("b")), Section("C", listOf("c"))))
        lateinit var rows: List<RowModel>
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            rows = (0 until 3).map { emitted.last().lease(it, 0).item }
            source.insertSection(0, "N", listOf("n"))
            advanceUntilIdle()
            source.removeSection(2) // B
            advanceUntilIdle()
            source.moveSection(2, 0) // C first
            advanceUntilIdle()
        }

        deltas.assertSectionedOracle()
        val last = deltas.last()
        assertEquals(listOf("C", "N", "A"), last.sections.map { it.header })
        assertSame(rows[2], last.lease(0, 0).item)
        assertSame(rows[0], last.lease(2, 0).item)
        assertEquals("n", last.lease(1, 0).item.value)
    }

    @Test
    fun headerUpdateKeepsTheSectionsRows() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a"))))
        lateinit var row: RowModel
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            row = emitted.last().lease(0, 0).item
            source.updateSectionHeader(0, "A'")
            advanceUntilIdle()
        }

        assertIs<SectionedChange.Sections>(deltas.last().change)
        assertSame(row, deltas.last().lease(0, 0).item)
    }

    @Test
    fun updateRebuildsOnlyThatItemsRow() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1", "a2")), Section("B", listOf("b1"))))
        lateinit var before: List<RowModel>
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            before = listOf(emitted.last().lease(0, 0).item, emitted.last().lease(0, 1).item, emitted.last().lease(1, 0).item)
            built = 0
            source.setItem(0, 1, "a2'")
            advanceUntilIdle()
        }

        val last = deltas.last()
        assertEquals(SectionedChange.Items(0, Mutation.Update(1)), last.change)
        assertEquals(1, built)
        assertSame(before[0], last.lease(0, 0).item)
        assertSame(before[2], last.lease(1, 0).item)
        val rebuilt = last.lease(0, 1).item
        assertNotSame(before[1], rebuilt)
        assertEquals("a2'", rebuilt.value)
        assertEquals(1, built)
    }

    @Test
    fun reloadClearsTheCache() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a"))))
        lateinit var lease: ItemLease<RowModel>
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            lease = emitted.last().lease(0, 0)
            source.reload(listOf(Section("A", listOf("a"))))
            advanceUntilIdle()
        }

        val last = deltas.last()
        assertIs<SectionedChange.Reload>(last.change)
        val lazy = last.sections[0].items as LazyList<RowModel>
        assertFalse(lazy.isAcquired(0))
        val replacement = last.lease(0, 0).item
        assertNotSame(lease.item, replacement)
        lease.release() // A lease from before the reload cannot evict its replacement.
        assertTrue(lazy.isAcquired(0))
    }

    @Test
    fun aSupersededSnapshotNeverChangesTheLiveCache() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1", "a2")), Section("B", listOf("b1"))))
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            emitted.last().lease(0, 0)
            source.appendItem(1, "b2")
            advanceUntilIdle()
        }

        val old = deltas[0].sections.map { it.items as LeasedLazyList<RowModel> }
        val live = deltas[1].sections.map { it.items as LazyList<RowModel> }
        assertTrue(live[0].isAcquired(0))

        // Neither the untouched section nor the mutated one accepts lifecycle calls from the old snapshot.
        old[0].release(0)
        old[0].releaseAll()
        old[1].releaseAll()
        assertNull(old[0].acquireItem(1))
        val peeked = old[0].acquire(1)
        assertTrue(live[0].isAcquired(0))
        assertFalse(live[0].isAcquired(1))
        assertNotSame((peeked as com.latenighthack.deltalist.SoftValue.Present).value, deltas[1].lease(0, 1).item)
    }

    @Test
    fun removedSectionsReleaseTheirLeasesHarmlessly() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a")), Section("B", listOf("b"))))
        lateinit var lease: ItemLease<RowModel>
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            lease = emitted.last().lease(0, 0)
            source.removeSection(0)
            advanceUntilIdle()
        }
        lease.release()
        lease.release()
        assertEquals(listOf(listOf("b")), deltas.last().peekValues())
    }

    @Test
    fun aChangeThatDoesNotFitIsDeliveredAsReload() = runTest {
        val upstream = flowOf(
            SectionedDelta(listOf(Section("A", listOf("a"))), SectionedChange.Reload),
            // Claims one insert but the section grew by two.
            SectionedDelta(listOf(Section("A", listOf("a", "b", "c"))), SectionedChange.Items(0, Mutation.Insert(1))),
            SectionedDelta(listOf(Section("A", listOf("a")), Section("B", listOf("b"))), SectionedChange.Sections(SectionMutation.Insert(5))),
        )
        val deltas = collectDriven(upstream.lazyMapItems(::row)) { }
        assertEquals(3, deltas.size)
        assertTrue(deltas.all { it.change is SectionedChange.Reload })
        assertEquals(listOf(listOf("a"), listOf("b")), deltas.last().peekValues())
    }

    @Test
    fun groupedRowsSurviveASectionComingAndGoing() = runTest {
        data class Entry(val category: String, val name: String)
        val source = MutableStateFlow(Delta(listOf(Entry("Personal", "p"), Entry("Work", "w1"), Entry("Work", "w2")), Change.Reload))
        val connections = source
            .groupBy(keySelector = { it.category }, headerMapper = { category, _ -> category.uppercase() })
            .lazyMapItems { entry -> RowModel(entry.name).also { built++ } }

        lateinit var work: List<RowModel>
        lateinit var hidden: RowModel
        val deltas = collectWith(connections) { emitted ->
            work = listOf(emitted.last().lease(1, 0).item, emitted.last().lease(1, 1).item)
            // A filter hides the only Personal connection, then it comes back.
            step(source, Delta(listOf(Entry("Work", "w1"), Entry("Work", "w2")), Change.Mutations(Mutation.Remove(0))))
            hidden = emitted.last().lease(0, 0).item
            step(source, Delta(listOf(Entry("Personal", "p"), Entry("Work", "w1"), Entry("Work", "w2")), Change.Mutations(Mutation.Insert(0))))
        }

        deltas.assertSectionedOracle()
        assertEquals(SectionedChange.Sections(SectionMutation.Remove(0)), deltas[1].change)
        assertEquals(SectionedChange.Sections(SectionMutation.Insert(0)), deltas[2].change)
        assertSame(work[0], hidden)
        assertSame(work[0], deltas[2].lease(1, 0).item)
        assertSame(work[1], deltas[2].lease(1, 1).item)
        assertEquals(listOf("PERSONAL", "WORK"), deltas.last().sections.map { it.header })
    }

    @Test
    fun fuzzIsOracleConsistentAndKeepsCachedRows() = runTest {
        val seed = 0x1A2F
        val rng = Random(seed)
        repeat(200) { trial ->
            var counter = 0
            val source = mutableSectionedDeltaListOf(List(rng.nextInt(0, 4)) {
                Section("S${counter++}", List(rng.nextInt(0, 4)) { "v${counter++}" })
            })
            try {
                val deltas = collectWith(source.lazyMapItems { RowModel(it) }) { emitted ->
                    repeat(rng.nextInt(1, 12)) {
                        val delta = emitted.last()
                        // Pin a random row so the cache has something to follow.
                        delta.sections.indices.randomOrNull(rng)?.let { s ->
                            delta.sections[s].items.size.takeIf { it > 0 }?.let { delta.lease(s, rng.nextInt(it)) }
                        }
                        source.randomEdit(rng) { "v${counter++}" }
                        advanceUntilIdle()
                    }
                }
                deltas.assertSectionedOracle()
                assertEquals(source.value.map { it.items.softLoadedItems() }, deltas.last().peekValues())
                deltas.assertAcquiredRowsMatchTheirItems()
            } catch (t: Throwable) {
                throw AssertionError("fuzz trial #$trial (seed=$seed) failed: ${t.message}", t)
            }
        }
    }

    // ==================== Swift bridges ====================

    @Test
    fun bridgesReadThroughTheCacheWithoutMapping() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1", "a2"))))
        lateinit var row: RowModel
        val deltas = collectWith(source.lazyMapItems(::row)) { emitted ->
            row = emitted.last().acquireItemAt(0, 1)!!.item
            source.insertItem(0, 0, "a0")
            advanceUntilIdle()
        }

        val last = deltas.last()
        built = 0
        assertEquals(3, last.getLoadedItemCountAt(0))
        assertEquals(0, last.getLoadedItemCountAt(1))
        assertEquals(0, built)
        assertSame(row, last.acquireItemAt(0, 2)!!.item)
        assertNull(last.acquireItemAt(0, 3))
        assertNull(last.acquireItemAt(1, 0))
        assertNotSame(row, last.getItemAt(0, 2)) // The plain peek never reads the cache.
    }

    @Test
    fun trackedStableIdsMatchTheTracker() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("A", listOf("a1")), Section("B", listOf("b1"))))
        val deltas = collectDriven(source) {
            source.insertSection(0, "N", listOf("n"))
            advanceUntilIdle()
            source.appendItem(2, "b2")
            advanceUntilIdle()
        }

        val tracker = SectionedStableIds()
        var previous: com.latenighthack.deltalist.SectionedIds? = null
        for (delta in deltas) {
            val expected = tracker.apply(delta)
            val tracked = delta.trackStableIds(previous)
            assertEquals(expected.sectionIds, tracked.sectionIds)
            delta.sections.forEachIndexed { s, section ->
                assertEquals(expected.sectionIds[s], tracked.sectionId(s))
                for (i in 0 until section.items.size) assertEquals(expected.itemId(s, i), tracked.itemId(s, i))
            }
            previous = tracked
        }
    }

    /** Like [collectDriven], but [drive] sees the emissions collected so far. */
    private fun <T> TestScope.collectWith(flow: Flow<T>, drive: TestScope.(emitted: List<T>) -> Unit): List<T> {
        val out = mutableListOf<T>()
        val job = launch { flow.collect { out.add(it) } }
        advanceUntilIdle()
        drive(out)
        advanceUntilIdle()
        job.cancel()
        return out
    }

    private fun <S> List<SectionedDelta<S, RowModel>>.assertAcquiredRowsMatchTheirItems() {
        val last = last()
        last.sections.forEach { section ->
            val lazy = section.items as LeasedLazyList<RowModel>
            val values = section.items.softLoadedItems().map { it.value }
            for (i in 0 until lazy.size) {
                if (lazy.isAcquired(i)) assertEquals(values[i], lazy.acquireItem(i)!!.item.value, "cached row at $i")
            }
        }
    }

    private fun com.latenighthack.deltalist.MutableSectionedDeltaList<String, String>.randomEdit(rng: Random, next: () -> String) {
        val sections = value
        val nonEmpty = sections.indices.filter { sections[it].items.size > 0 }
        when (if (sections.isEmpty()) 0 else rng.nextInt(9)) {
            0 -> insertSection(rng.nextInt(sections.size + 1), next(), List(rng.nextInt(0, 3)) { next() })
            1 -> removeSection(rng.nextInt(sections.size))
            2 -> moveSection(rng.nextInt(sections.size), rng.nextInt(sections.size))
            3 -> updateSectionHeader(rng.nextInt(sections.size), next())
            4 -> { val s = rng.nextInt(sections.size); insertItem(s, rng.nextInt(sections[s].items.size + 1), next()) }
            5 -> nonEmpty.randomOrNull(rng)?.let { removeItem(it, rng.nextInt(sections[it].items.size)) }
            6 -> nonEmpty.randomOrNull(rng)?.let { setItem(it, rng.nextInt(sections[it].items.size), next()) }
            7 -> nonEmpty.randomOrNull(rng)?.let { val n = sections[it].items.size; moveItem(it, rng.nextInt(n), rng.nextInt(n)) }
            else -> if (rng.nextInt(4) == 0) reload(sections) else updateSection(rng.nextInt(sections.size)) { items ->
                if (items.isNotEmpty()) items.removeAt(rng.nextInt(items.size))
                items.add(rng.nextInt(items.size + 1), next())
            }
        }
    }
}

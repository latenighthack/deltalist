@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist

import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class BatchMutationTest {
    @Test fun mutableAndBuilderBatchesUseFinalSnapshotCoordinates() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B"))
        val history = mutableListOf<Delta<String>>()
        backgroundScope.launch { source.collect { history += it } }
        runCurrent()
        source.update { it.add("C"); it.removeAt(0) }
        runCurrent()
        assertEquals(listOf("B", "C"), applyChange(listOf("A", "B"), history.last()))

        val built = deltaList(listOf("A", "B")) { list ->
            list.batch { add("C"); removeAt(0) }
        }.toList()
        assertEquals(listOf("B", "C"), applyChange(listOf("A", "B"), built.last()))
    }

    @Test fun normalizationPreservesRetainedIdentityIncludingUpdates() = runTest {
        val source = mutableDeltaListOf(listOf("A", "B"))
        val history = mutableListOf<Delta<StableItem<String>>>()
        backgroundScope.launch { source.withStableIds().collect { history += it } }
        runCurrent()
        val oldIds = history.last().items.softLoadedItems().map { it.stableId }
        source.update { it[1] = "B2"; it.add(0, "X"); it.add(0, "Y") }
        runCurrent()
        val rows = history.last().items.softLoadedItems()
        assertEquals(listOf("Y", "X", "A", "B2"), rows.map { it.value })
        assertEquals(oldIds, rows.takeLast(2).map { it.stableId })
        assertIs<Change.Mutations>(history.last().change)
    }

    @Test fun sectionBatchesReconstructWithoutReload() = runTest {
        val source = mutableSectionedDeltaListOf(listOf(Section("S", listOf("A", "B"))))
        val history = mutableListOf<SectionedDelta<String, String>>()
        backgroundScope.launch { source.collect { history += it } }
        runCurrent()
        source.updateSection(0) { it.add("C"); it.removeAt(0) }
        runCurrent()
        val change = assertIs<SectionedChange.Items>(history.last().change)
        assertEquals(listOf("B", "C"), applyChange(listOf("A", "B"), Change.Mutations(change.mutations), listOf("B", "C")))
    }

    @Test fun arbitraryBatchesReconstructWithDuplicateValues() {
        val random = Random(7193)
        repeat(400) {
            val initial = List(random.nextInt(8)) { random.nextInt(4) }
            val tracked = TrackedMutableList(initial)
            repeat(20) {
                when (random.nextInt(4)) {
                    0 -> tracked.add(random.nextInt(tracked.size + 1), random.nextInt(4))
                    1 -> if (tracked.isNotEmpty()) tracked.removeAt(random.nextInt(tracked.size))
                    2 -> if (tracked.isNotEmpty()) tracked[random.nextInt(tracked.size)] = random.nextInt(4)
                    3 -> if (tracked.isNotEmpty()) tracked.move(random.nextInt(tracked.size), random.nextInt(tracked.size))
                }
            }
            val final = tracked.toList()
            assertEquals(final, applyChange(initial, Change.Mutations(tracked.toMutations()), final))
        }
    }

    @Test fun aliasedBulkInputsKeepTheirOriginalContentsAndCounts() = runTest {
        for (atFront in listOf(false, true)) {
            val initial = listOf(1, 2)
            val source = mutableDeltaListOf(initial)
            val history = mutableListOf<Delta<Int>>()
            backgroundScope.launch { source.collect { history += it } }
            runCurrent()
            source.update { if (atFront) it.addAll(0, it) else it.addAll(it) }
            runCurrent()
            assertEquals(listOf(1, 2, 1, 2), source.value)
            assertEquals(source.value, applyChange(initial, history.last()))

            val batched = deltaList(initial) { list ->
                list.batch { if (atFront) addAll(0, this) else addAll(this) }
            }.toList()
            assertEquals(listOf(1, 2, 1, 2), applyChange(initial, batched.last()))

            val immediate = deltaList(initial) { list ->
                if (atFront) list.addAll(0, list) else list.addAll(list)
            }.toList()
            assertEquals(listOf(1, 2, 1, 2), applyChange(initial, immediate.last()))
        }
    }

    @Test fun bulkInsertSnapshotsAnOverlappingSublist() {
        val initial = listOf(1, 2, 3)
        val tracked = TrackedMutableList(initial)
        tracked.addAll(0, tracked.subList(1, 3))
        assertEquals(listOf(2, 3, 1, 2, 3), tracked.toList())
        assertEquals(tracked.toList(), applyChange(initial, Change.Mutations(tracked.toMutations()), tracked.toList()))
    }
}

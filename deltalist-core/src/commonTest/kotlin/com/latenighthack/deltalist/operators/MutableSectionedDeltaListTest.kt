@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.MutableSectionedDeltaList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MutableSectionedDeltaListTest {

    private fun initial() = listOf(
        Section("A", listOf(1, 2)),
        Section("B", listOf(3, 4, 5))
    )

    @Test
    fun allOperationsAreOracleConsistent() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val deltas: List<SectionedDelta<String, Int>> = collectDriven(list) {
            list.appendItem(0, 9); advanceUntilIdle()
            list.insertItem(1, 0, 8); advanceUntilIdle()
            list.removeItem(0, 0); advanceUntilIdle()
            list.setItem(1, 2, 77); advanceUntilIdle()
            list.moveItem(1, 0, 2); advanceUntilIdle()
            list.appendSection("C", listOf(100)); advanceUntilIdle()
            list.insertSection(0, "Z", listOf(0)); advanceUntilIdle()
            list.updateSectionHeader(0, "Z2"); advanceUntilIdle()
            list.moveSection(0, 2); advanceUntilIdle()
            list.removeSection(1); advanceUntilIdle()
            list.updateSection(0) { it.add(0, -1); it.removeAt(it.size - 1) }; advanceUntilIdle()
            list.reload(listOf(Section("Only", listOf(1)))); advanceUntilIdle()
        }

        deltas.assertSectionedOracle()
        assertEquals(listOf("Only"), deltas.last().sections.map { it.header })
        assertEquals(listOf(1), deltas.last().sections[0].items.softLoadedItems())
    }

    @Test
    fun emptyOrNoOpMutationsEmitNothingExtra() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val deltas = collectDriven(list) {
            list.moveItem(0, 1, 1); advanceUntilIdle()      // same index -> no emission
            list.moveSection(0, 0); advanceUntilIdle()       // same index -> no emission
            list.updateSection(1) { /* no change */ }; advanceUntilIdle()
            list.appendItem(0, 42); advanceUntilIdle()       // a real emission
        }

        deltas.assertSectionedOracle()
        // initial reload + exactly one real mutation emission
        assertEquals(2, deltas.size)
        assertEquals(SectionedChange.Items::class, deltas.last().change::class)
    }

    @Test
    fun flattenCrossCheckIsFlatOracleConsistent() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val flat = list.flatten(
            header = { h -> "H:$h" },
            item = { i -> "I:$i" }
        )

        val deltas = collectDriven(flat) {
            list.appendItem(0, 9); advanceUntilIdle()
            list.removeItem(1, 0); advanceUntilIdle()
            list.appendSection("C", listOf(100)); advanceUntilIdle()
            list.setItem(0, 0, 55); advanceUntilIdle()
        }

        deltas.assertFlatOracle()
    }

    @Test
    fun itemMutationsRejectPartialSectionsWithoutPublishingOrRequesting() = runTest {
        var callbackCalls = 0
        val edits: List<(MutableSectionedDeltaList<String, Int>) -> Unit> = listOf(
            { it.appendItem(0, 4) },
            { it.insertItem(0, 0, 4) },
            { it.removeItem(0, 0) },
            { it.setItem(0, 0, 4) },
            { it.moveItem(0, 0, 1) },
            { it.updateSection(0) { items -> callbackCalls++; items.add(4) } }
        )
        for (edit in edits) {
            var requests = 0
            val partial = object : AbstractSoftList<Int>() {
                override val size = 3
                override fun softGet(index: Int): SoftValue<Int>? = when (index) {
                    0 -> SoftValue.Present(1)
                    1 -> SoftValue.NotLoaded { requests++ }
                    2 -> SoftValue.Present(3)
                    else -> null
                }
            }
            val source = mutableSectionedDeltaListOf(listOf(Section("A", partial)))
            val initial = source.value
            val deltas = collectDriven(source) {
                assertFailsWith<IllegalStateException> { edit(source) }
                advanceUntilIdle()
            }
            assertSame(initial, source.value)
            assertSame(partial, source.value[0].items)
            assertEquals(1, deltas.size)
            assertEquals(0, requests)
        }
        assertEquals(0, callbackCalls)
    }

    @Test
    fun rejectsAnEnormousUnloadedEstimateWithoutAllocatingOrMutating() {
        var peeks = 0
        var requests = 0
        val partial = object : AbstractSoftList<Int>() {
            override val size = Int.MAX_VALUE
            override fun softGet(index: Int): SoftValue<Int> {
                peeks++
                return SoftValue.NotLoaded { requests++ }
            }
        }
        val source = mutableSectionedDeltaListOf(listOf(Section("A", partial)))
        val initial = source.value
        assertFailsWith<IllegalStateException> { source.appendItem(0, 1) }
        assertSame(initial, source.value)
        assertSame(partial, source.value[0].items)
        assertEquals(1, peeks)
        assertEquals(0, requests)
    }
}

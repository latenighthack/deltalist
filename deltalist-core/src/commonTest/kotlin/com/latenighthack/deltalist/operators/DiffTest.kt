package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    data class Item(val id: String, val name: String)

    @Test
    fun diffInitialEmissionIsReload() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(Item("1", "A"), Item("2", "B")), results[0].items)
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun diffSingleInsertAtEnd() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(2, insert.index)
        assertEquals(1, insert.count)
    }

    @Test
    fun diffSingleInsertAtStart() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("0", "Z"), Item("1", "A"), Item("2", "B"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
        assertEquals(1, insert.count)
    }

    @Test
    fun diffSingleInsertInMiddle() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(1, insert.index)
        assertEquals(1, insert.count)
    }

    @Test
    fun diffMultipleConsecutiveInserts() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"), Item("4", "D"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        // Should be coalesced into single insert
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(1, insert.index)
        assertEquals(3, insert.count)
    }

    @Test
    fun diffSingleRemoveFromEnd() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(2, remove.index)
        assertEquals(1, remove.count)
    }

    @Test
    fun diffSingleRemoveFromStart() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("2", "B"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(1, remove.count)
    }

    @Test
    fun diffSingleRemoveFromMiddle() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(1, remove.index)
        assertEquals(1, remove.count)
    }

    @Test
    fun diffSingleUpdate() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B-Updated"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val update = change.operations[0] as Mutation.Update
        assertEquals(1, update.index)
        assertEquals(1, update.count)
    }

    @Test
    fun diffMultipleUpdates() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A-Updated"), Item("2", "B"), Item("3", "C-Updated"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        // Two non-consecutive updates should remain separate
        assertEquals(2, change.operations.size)
        val update1 = change.operations[0] as Mutation.Update
        assertEquals(0, update1.index)
        val update2 = change.operations[1] as Mutation.Update
        assertEquals(2, update2.index)
    }

    @Test
    fun diffSimpleMove() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        // Move item 1 to end
        source.value = listOf(Item("2", "B"), Item("3", "C"), Item("1", "A"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        // Should have move(s)
        assertTrue(change.operations.any { it is Mutation.Move })
    }

    @Test
    fun diffSwapTwoItems() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("2", "B"), Item("1", "A"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertTrue(change.operations.any { it is Mutation.Move })
    }

    @Test
    fun diffComplexOperation() = runTest {
        // Old: A, B, C, D
        // New: B, E, C-Updated, A
        // Operations: remove D, insert E at 1, update C, move A to end
        val source = MutableStateFlow(listOf(
            Item("A", "Alpha"),
            Item("B", "Beta"),
            Item("C", "Charlie"),
            Item("D", "Delta")
        ))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(
            Item("B", "Beta"),
            Item("E", "Echo"),
            Item("C", "Charlie-Updated"),
            Item("A", "Alpha")
        )

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertTrue(change.operations.isNotEmpty())
        // Verify the final state is correct
        assertEquals(listOf(
            Item("B", "Beta"),
            Item("E", "Echo"),
            Item("C", "Charlie-Updated"),
            Item("A", "Alpha")
        ), results[1].items)
    }

    @Test
    fun diffEmptyToPopulated() = runTest {
        val source = MutableStateFlow(emptyList<Item>())
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertTrue(results[0].change is Change.Reload)
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
        assertEquals(2, insert.count)
    }

    @Test
    fun diffPopulatedToEmpty() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = emptyList()

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        // Should have removes for all items
        val totalRemoved = change.operations.filterIsInstance<Mutation.Remove>().sumOf { it.count }
        assertEquals(2, totalRemoved)
    }

    @Test
    fun diffMultipleEmissions() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"))

        delay(50)
        source.value = listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))

        delay(50)
        source.value = listOf(Item("2", "B"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(4, results.size)
        assertTrue(results[0].change is Change.Reload)
        assertTrue(results[1].change is Change.Mutations)
        assertTrue(results[2].change is Change.Mutations)
        assertTrue(results[3].change is Change.Mutations)
    }

    @Test
    fun diffReverseList() = runTest {
        val source = MutableStateFlow(listOf(
            Item("1", "A"),
            Item("2", "B"),
            Item("3", "C"),
            Item("4", "D")
        ))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        source.value = listOf(
            Item("4", "D"),
            Item("3", "C"),
            Item("2", "B"),
            Item("1", "A")
        )

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        assertTrue(change.operations.any { it is Mutation.Move })
        // Verify final order is correct
        assertEquals(listOf("4", "3", "2", "1"), results[1].items.map { it.id })
    }

    @Test
    fun diffRemoveAndInsertSamePosition() = runTest {
        val source = MutableStateFlow(listOf(Item("1", "A"), Item("2", "B")))
        val deltaFlow = source.asDeltaList { it.id }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            deltaFlow.collect { results.add(it) }
        }

        delay(50)
        // Replace item at position 1
        source.value = listOf(Item("1", "A"), Item("3", "C"))

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change as Change.Mutations
        // Should have a remove and an insert
        assertTrue(change.operations.any { it is Mutation.Remove })
        assertTrue(change.operations.any { it is Mutation.Insert })
    }
}

package com.latenighthack.deltalist

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaFlowBuilderTest {

    data class Item(val id: String, val name: String)

    @Test
    fun initialEmissionIsReload() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(Item("1", "A"), Item("2", "B")), results[0].items)
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun emptyInitialEmissionIsReload() = runTest {
        val flow = deltaList<Item> { }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertTrue(results[0].items.isEmpty())
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun addEmitsImmediately() = runTest {
        val flow = deltaList<Item> { list ->
            list.add(Item("1", "A"))
            list.add(Item("2", "B"))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(3, results.size)

        // First: reload with empty
        assertTrue(results[0].change is Change.Reload)

        // Second: insert first item
        val change1 = results[1].change as Change.Mutations
        assertEquals(1, change1.operations.size)
        val insert1 = change1.operations[0] as Mutation.Insert
        assertEquals(0, insert1.index)
        assertEquals(listOf(Item("1", "A")), results[1].items)

        // Third: insert second item
        val change2 = results[2].change as Change.Mutations
        assertEquals(1, change2.operations.size)
        val insert2 = change2.operations[0] as Mutation.Insert
        assertEquals(1, insert2.index)
        assertEquals(listOf(Item("1", "A"), Item("2", "B")), results[2].items)
    }

    @Test
    fun batchGroupsMutations() = runTest {
        val flow = deltaList<Item> { list ->
            list.batch {
                add(Item("1", "A"))
                add(Item("2", "B"))
                add(Item("3", "C"))
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        // First: reload
        assertTrue(results[0].change is Change.Reload)

        // Second: batched inserts
        val change = results[1].change as Change.Mutations
        assertEquals(3, change.operations.size)
        assertTrue(change.operations.all { it is Mutation.Insert })
        assertEquals(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")), results[1].items)
    }

    @Test
    fun removeEmitsImmediately() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            list.removeAt(0)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(listOf(Item("2", "B")), results[1].items)
    }

    @Test
    fun setEmitsUpdate() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            list.set(0, Item("1", "A-Updated"))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val update = change.operations[0] as Mutation.Update
        assertEquals(0, update.index)
        assertEquals(listOf(Item("1", "A-Updated")), results[1].items)
    }

    @Test
    fun moveEmitsMove() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))) { list ->
            list.move(0, 2)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val move = change.operations[0] as Mutation.Move
        assertEquals(0, move.fromIndex)
        assertEquals(2, move.toIndex)
        assertEquals(listOf(Item("2", "B"), Item("3", "C"), Item("1", "A")), results[1].items)
    }

    @Test
    fun clearEmitsRemove() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            list.clear()
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(2, remove.count)
        assertTrue(results[1].items.isEmpty())
    }

    @Test
    fun reloadEmitsReload() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            list.reload(listOf(Item("2", "B"), Item("3", "C")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertTrue(results[0].change is Change.Reload)
        assertTrue(results[1].change is Change.Reload)
        assertEquals(listOf(Item("2", "B"), Item("3", "C")), results[1].items)
    }

    @Test
    fun reactToExternalFlow() = runTest {
        val events = MutableSharedFlow<String>()

        val flow = deltaList<Item> { list ->
            events.collect { event ->
                when {
                    event.startsWith("add:") -> {
                        val name = event.removePrefix("add:")
                        list.add(Item(name, name))
                    }
                    event.startsWith("remove:") -> {
                        val name = event.removePrefix("remove:")
                        list.remove(Item(name, name))
                    }
                }
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        events.emit("add:A")
        delay(50)
        events.emit("add:B")
        delay(50)
        events.emit("remove:A")
        delay(50)
        job.cancel()

        assertEquals(4, results.size)
        // Initial reload
        assertTrue(results[0].change is Change.Reload)
        // Add A
        assertEquals(listOf(Item("A", "A")), results[1].items)
        // Add B
        assertEquals(listOf(Item("A", "A"), Item("B", "B")), results[2].items)
        // Remove A
        assertEquals(listOf(Item("B", "B")), results[3].items)
    }

    @Test
    fun listIsReadableDuringMutations() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            // Can read list before mutation
            assertEquals(1, list.size)
            assertEquals(Item("1", "A"), list[0])

            list.add(Item("2", "B"))

            // Can read list after mutation
            assertEquals(2, list.size)
            assertEquals(Item("2", "B"), list[1])
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
    }

    @Test
    fun batchWithMixedOperations() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            list.batch {
                add(Item("3", "C"))
                removeAt(0)
                set(0, Item("2", "B-Updated"))
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(3, change.operations.size)
        assertTrue(change.operations[0] is Mutation.Insert)
        assertTrue(change.operations[1] is Mutation.Remove)
        assertTrue(change.operations[2] is Mutation.Update)
        assertEquals(listOf(Item("2", "B-Updated"), Item("3", "C")), results[1].items)
    }

    @Test
    fun addAtIndexEmitsInsert() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("3", "C"))) { list ->
            list.add(1, Item("2", "B"))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(1, insert.index)
        assertEquals(1, insert.count)
        assertEquals(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")), results[1].items)
    }

    @Test
    fun addAllEmitsInsert() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            list.addAll(listOf(Item("2", "B"), Item("3", "C")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(1, insert.index)
        assertEquals(2, insert.count)
        assertEquals(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")), results[1].items)
    }

    @Test
    fun addAllAtIndexEmitsInsert() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("4", "D"))) { list ->
            list.addAll(1, listOf(Item("2", "B"), Item("3", "C")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(1, insert.index)
        assertEquals(2, insert.count)
        assertEquals(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"), Item("4", "D")), results[1].items)
    }

    @Test
    fun addAllEmptyCollectionNoEmission() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            list.addAll(emptyList())
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        // Only initial reload, no mutation for empty addAll
        assertEquals(1, results.size)
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun removeByElementEmitsRemove() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))) { list ->
            val removed = list.remove(Item("2", "B"))
            assertTrue(removed)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(1, remove.index)
        assertEquals(listOf(Item("1", "A"), Item("3", "C")), results[1].items)
    }

    @Test
    fun removeNonExistentElementNoEmission() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            val removed = list.remove(Item("999", "NotFound"))
            assertTrue(!removed)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        // Only initial reload, no mutation for non-existent remove
        assertEquals(1, results.size)
    }

    @Test
    fun clearEmptyListNoEmission() = runTest {
        val flow = deltaList<Item> { list ->
            list.clear()
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        // Only initial reload, no mutation for clearing empty list
        assertEquals(1, results.size)
    }

    @Test
    fun moveSameIndexNoEmission() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            list.move(0, 0)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        // Only initial reload, no mutation for move to same index
        assertEquals(1, results.size)
    }

    @Test
    fun batchWithMoveOperation() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))) { list ->
            list.batch {
                move(0, 2)
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val move = change.operations[0] as Mutation.Move
        assertEquals(0, move.fromIndex)
        assertEquals(2, move.toIndex)
        assertEquals(listOf(Item("2", "B"), Item("3", "C"), Item("1", "A")), results[1].items)
    }

    @Test
    fun emptyBatchNoEmission() = runTest {
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            list.batch {
                // No operations
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        // Only initial reload, no emission for empty batch
        assertEquals(1, results.size)
    }

    @Test
    fun multipleBatchesInSequence() = runTest {
        val flow = deltaList<Item> { list ->
            list.batch {
                add(Item("1", "A"))
                add(Item("2", "B"))
            }
            list.batch {
                add(Item("3", "C"))
                removeAt(0)
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(3, results.size)

        // First batch
        val change1 = results[1].change as Change.Mutations
        assertEquals(2, change1.operations.size)
        assertEquals(listOf(Item("1", "A"), Item("2", "B")), results[1].items)

        // Second batch
        val change2 = results[2].change as Change.Mutations
        assertEquals(2, change2.operations.size)
        assertEquals(listOf(Item("2", "B"), Item("3", "C")), results[2].items)
    }

    @Test
    fun mixImmediateAndBatchedOperations() = runTest {
        val flow = deltaList<Item> { list ->
            list.add(Item("1", "A"))  // Immediate
            list.batch {
                add(Item("2", "B"))
                add(Item("3", "C"))
            }
            list.removeAt(0)  // Immediate
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(4, results.size)

        // Initial reload
        assertTrue(results[0].change is Change.Reload)

        // Immediate add
        val change1 = results[1].change as Change.Mutations
        assertEquals(1, change1.operations.size)
        assertTrue(change1.operations[0] is Mutation.Insert)

        // Batched adds
        val change2 = results[2].change as Change.Mutations
        assertEquals(2, change2.operations.size)

        // Immediate remove
        val change3 = results[3].change as Change.Mutations
        assertEquals(1, change3.operations.size)
        assertTrue(change3.operations[0] is Mutation.Remove)
        assertEquals(listOf(Item("2", "B"), Item("3", "C")), results[3].items)
    }

    @Test
    fun listContainsWorks() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            assertTrue(list.contains(Item("1", "A")))
            assertTrue(!list.contains(Item("999", "NotFound")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
    }

    @Test
    fun listIndexOfWorks() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            assertEquals(0, list.indexOf(Item("1", "A")))
            assertEquals(1, list.indexOf(Item("2", "B")))
            assertEquals(-1, list.indexOf(Item("999", "NotFound")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
    }

    @Test
    fun listIterationWorks() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C"))) { list ->
            val collected = mutableListOf<Item>()
            for (item in list) {
                collected.add(item)
            }
            assertEquals(listOf(Item("1", "A"), Item("2", "B"), Item("3", "C")), collected)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
    }

    @Test
    fun setReturnsOldValue() = runTest {
        var oldValue: Item? = null
        val flow = deltaList(listOf(Item("1", "A"))) { list ->
            oldValue = list.set(0, Item("1", "A-Updated"))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(Item("1", "A"), oldValue)
    }

    @Test
    fun removeAtReturnsRemovedElement() = runTest {
        var removed: Item? = null
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            removed = list.removeAt(0)
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(Item("1", "A"), removed)
    }

    @Test
    fun batchClearAndRepopulate() = runTest {
        val flow = deltaList(listOf(Item("1", "A"), Item("2", "B"))) { list ->
            list.batch {
                clear()
                add(Item("3", "C"))
                add(Item("4", "D"))
                add(Item("5", "E"))
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(4, change.operations.size)
        assertTrue(change.operations[0] is Mutation.Remove)
        assertTrue(change.operations[1] is Mutation.Insert)
        assertTrue(change.operations[2] is Mutation.Insert)
        assertTrue(change.operations[3] is Mutation.Insert)
        assertEquals(listOf(Item("3", "C"), Item("4", "D"), Item("5", "E")), results[1].items)
    }

    @Test
    fun reloadAfterMutations() = runTest {
        val flow = deltaList<Item> { list ->
            list.add(Item("1", "A"))
            list.add(Item("2", "B"))
            list.reload(listOf(Item("X", "New"), Item("Y", "List")))
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(4, results.size)

        // Initial reload (empty)
        assertTrue(results[0].change is Change.Reload)
        assertTrue(results[0].items.isEmpty())

        // Add mutations
        assertTrue(results[1].change is Change.Mutations)
        assertTrue(results[2].change is Change.Mutations)

        // Final reload
        assertTrue(results[3].change is Change.Reload)
        assertEquals(listOf(Item("X", "New"), Item("Y", "List")), results[3].items)
    }

    @Test
    fun batchAddAllVariants() = runTest {
        val flow = deltaList<Item> { list ->
            list.batch {
                addAll(listOf(Item("1", "A"), Item("2", "B")))
                addAll(1, listOf(Item("X", "Middle")))
            }
        }

        val results = mutableListOf<Delta<Item>>()
        val job = launch {
            flow.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change as Change.Mutations
        assertEquals(2, change.operations.size)
        assertEquals(listOf(Item("1", "A"), Item("X", "Middle"), Item("2", "B")), results[1].items)
    }

    @Test
    fun multipleCollectorsGetSameEmissions() = runTest {
        val events = MutableSharedFlow<String>()

        val flow = deltaList<Item> { list ->
            events.collect { event ->
                if (event.startsWith("add:")) {
                    val name = event.removePrefix("add:")
                    list.add(Item(name, name))
                }
            }
        }

        val results1 = mutableListOf<Delta<Item>>()
        val results2 = mutableListOf<Delta<Item>>()

        val job1 = launch {
            flow.collect { results1.add(it) }
        }

        delay(50)

        val job2 = launch {
            flow.collect { results2.add(it) }
        }

        delay(50)
        events.emit("add:A")
        delay(50)

        job1.cancel()
        job2.cancel()

        // Each collector gets its own stream (cold flow behavior)
        // First collector: reload + add
        assertEquals(2, results1.size)
        // Second collector: its own reload + add
        assertEquals(2, results2.size)
    }
}

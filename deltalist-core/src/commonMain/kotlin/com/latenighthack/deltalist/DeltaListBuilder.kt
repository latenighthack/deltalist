package com.latenighthack.deltalist

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * A MutableList that also supports move operations.
 * Used within [ListMutatingDeltaList.batch] blocks.
 */
interface MutatingDeltaList<T> : MutableList<T> {
    /**
     * Moves an element from one index to another.
     */
    fun move(fromIndex: Int, toIndex: Int)
}

/**
 * A list that emits mutations to a DeltaList as they occur.
 *
 * By default, each operation (add, remove, set, etc.) emits immediately.
 * Use [batch] to group multiple operations into a single emission.
 *
 * Example:
 * ```
 * deltaList { list ->
 *     // Each add emits immediately
 *     list.add(Item("1", "First"))
 *     list.add(Item("2", "Second"))
 *
 *     // Batch multiple operations into one emission
 *     list.batch {
 *         add(Item("3", "Third"))
 *         add(Item("4", "Fourth"))
 *         removeAt(0)
 *     }
 * }
 * ```
 */
interface ListMutatingDeltaList<T> : List<T> {
    /**
     * Groups multiple mutations into a single emission.
     * The block receives a [MutatingDeltaList] for performing operations.
     */
    suspend fun batch(block: MutatingDeltaList<T>.() -> Unit)

    /**
     * Adds an element to the end of the list and emits immediately.
     */
    suspend fun add(element: T)

    /**
     * Inserts an element at the specified index and emits immediately.
     */
    suspend fun add(index: Int, element: T)

    /**
     * Adds all elements to the end of the list and emits immediately.
     */
    suspend fun addAll(elements: Collection<T>)

    /**
     * Inserts all elements at the specified index and emits immediately.
     */
    suspend fun addAll(index: Int, elements: Collection<T>)

    /**
     * Removes the first occurrence of the element and emits immediately.
     * Returns true if the element was found and removed.
     */
    suspend fun remove(element: T): Boolean

    /**
     * Removes the element at the specified index and emits immediately.
     * Returns the removed element.
     */
    suspend fun removeAt(index: Int): T

    /**
     * Replaces the element at the specified index and emits immediately.
     * Returns the previous element.
     */
    suspend fun set(index: Int, element: T): T

    /**
     * Removes all elements from the list and emits immediately.
     */
    suspend fun clear()

    /**
     * Moves an element from one index to another and emits immediately.
     */
    suspend fun move(fromIndex: Int, toIndex: Int)

    /**
     * Replaces all elements with a reload and emits immediately.
     */
    suspend fun reload(elements: List<T>)
}

@Deprecated("Use ListMutatingDeltaList instead", ReplaceWith("ListMutatingDeltaList<T>"))
typealias FlowMutatingDeltaList<T> = ListMutatingDeltaList<T>

/**
 * Creates a [DeltaList] using a builder pattern similar to [flow].
 *
 * The provided [block] receives a [ListMutatingDeltaList] that can be modified
 * to emit deltas. By default, each mutation emits immediately. Use [ListMutatingDeltaList.batch]
 * to group multiple operations into a single emission.
 *
 * The first emission is always a Reload with the initial list state.
 *
 * Example:
 * ```
 * fun itemsFlow(sourceFlow: Flow<Event>): DeltaList<Item> = deltaList { list ->
 *     sourceFlow.collect { event ->
 *         when (event) {
 *             is Event.Added -> list.add(event.item)
 *             is Event.Removed -> list.remove(event.item)
 *             is Event.BatchUpdate -> list.batch {
 *                 clear()
 *                 addAll(event.items)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param initial The initial list contents (defaults to empty)
 * @param block The builder block that receives the mutable list
 */
fun <T> deltaList(
    initial: List<T> = emptyList(),
    block: suspend (ListMutatingDeltaList<T>) -> Unit
): DeltaList<T> = flow {
    val list = ListMutatingDeltaListImpl(initial, this)
    // Emit initial state as reload
    emit(Delta(list.snapshot(), Change.Reload))
    // Run the builder block
    block(list)
}

@Deprecated("Use deltaList instead", ReplaceWith("deltaList(initial, block)"))
fun <T> deltaFlow(
    initial: List<T> = emptyList(),
    block: suspend (ListMutatingDeltaList<T>) -> Unit
): DeltaList<T> = deltaList(initial, block)

/**
 * Implementation of [MutatingDeltaList] that tracks mutations.
 */
internal class MutatingDeltaListImpl<T>(
    initial: List<T>
) : MutatingDeltaList<T>, AbstractMutableList<T>() {

    private val backing = initial.toMutableList()
    private val operations = mutableListOf<Mutation>()

    override val size: Int get() = backing.size

    override fun get(index: Int): T = backing[index]

    override fun add(index: Int, element: T) {
        backing.add(index, element)
        operations.add(Mutation.Insert(index, 1))
    }

    override fun addAll(elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false
        val index = backing.size
        backing.addAll(elements)
        operations.add(Mutation.Insert(index, elements.size))
        return true
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false
        backing.addAll(index, elements)
        operations.add(Mutation.Insert(index, elements.size))
        return true
    }

    override fun removeAt(index: Int): T {
        val removed = backing.removeAt(index)
        operations.add(Mutation.Remove(index, 1))
        return removed
    }

    override fun set(index: Int, element: T): T {
        val old = backing.set(index, element)
        operations.add(Mutation.Update(index, 1))
        return old
    }

    override fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val item = backing.removeAt(fromIndex)
        backing.add(toIndex, item)
        operations.add(Mutation.Move(fromIndex, toIndex))
    }

    override fun clear() {
        if (backing.isEmpty()) return
        val count = backing.size
        backing.clear()
        operations.add(Mutation.Remove(0, count))
    }

    fun toMutations(): List<Mutation> = operations.toList()

    fun toList(): List<T> = backing.toList()
}

/**
 * Internal implementation of [ListMutatingDeltaList].
 */
private class ListMutatingDeltaListImpl<T>(
    initial: List<T>,
    private val collector: FlowCollector<Delta<T>>
) : ListMutatingDeltaList<T>, AbstractList<T>() {

    private val backing = initial.toMutableList()

    fun snapshot(): List<T> = backing.toList()

    private suspend fun emitMutation(mutation: Mutation) {
        collector.emit(Delta(snapshot(), Change.Mutations(mutation)))
    }

    private suspend fun emitMutations(mutations: List<Mutation>) {
        if (mutations.isNotEmpty()) {
            collector.emit(Delta(snapshot(), Change.Mutations(mutations)))
        }
    }

    // List implementation (read-only operations)

    override val size: Int get() = backing.size

    override fun get(index: Int): T = backing[index]

    // FlowMutatingDeltaList implementation (suspend functions for mutations)

    override suspend fun batch(block: MutatingDeltaList<T>.() -> Unit) {
        val tracked = MutatingDeltaListImpl(backing.toList())
        tracked.block()
        // Apply changes to our backing list
        backing.clear()
        backing.addAll(tracked.toList())
        // Emit batched mutations
        emitMutations(tracked.toMutations())
    }

    override suspend fun add(element: T) {
        val index = backing.size
        backing.add(element)
        emitMutation(Mutation.Insert(index, 1))
    }

    override suspend fun add(index: Int, element: T) {
        backing.add(index, element)
        emitMutation(Mutation.Insert(index, 1))
    }

    override suspend fun addAll(elements: Collection<T>) {
        if (elements.isEmpty()) return
        val index = backing.size
        backing.addAll(elements)
        emitMutation(Mutation.Insert(index, elements.size))
    }

    override suspend fun addAll(index: Int, elements: Collection<T>) {
        if (elements.isEmpty()) return
        backing.addAll(index, elements)
        emitMutation(Mutation.Insert(index, elements.size))
    }

    override suspend fun remove(element: T): Boolean {
        val index = backing.indexOf(element)
        if (index == -1) return false
        backing.removeAt(index)
        emitMutation(Mutation.Remove(index, 1))
        return true
    }

    override suspend fun removeAt(index: Int): T {
        val removed = backing.removeAt(index)
        emitMutation(Mutation.Remove(index, 1))
        return removed
    }

    override suspend fun set(index: Int, element: T): T {
        val old = backing.set(index, element)
        emitMutation(Mutation.Update(index, 1))
        return old
    }

    override suspend fun clear() {
        if (backing.isEmpty()) return
        val count = backing.size
        backing.clear()
        emitMutation(Mutation.Remove(0, count))
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val item = backing.removeAt(fromIndex)
        backing.add(toIndex, item)
        emitMutation(Mutation.Move(fromIndex, toIndex))
    }

    override suspend fun reload(elements: List<T>) {
        backing.clear()
        backing.addAll(elements)
        collector.emit(Delta(snapshot(), Change.Reload))
    }
}

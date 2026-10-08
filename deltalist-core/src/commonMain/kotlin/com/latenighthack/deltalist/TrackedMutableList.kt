package com.latenighthack.deltalist

internal class TrackedMutableList<T>(
    initial: List<T>
) : AbstractMutableList<T>() {
    private val backing = initial.toMutableList()
    private val operations = mutableListOf<TrackedOperation>()

    override val size: Int get() = backing.size

    override fun contains(element: T): Boolean = backing.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean = backing.containsAll(elements)

    override fun get(index: Int): T = backing[index]

    override fun indexOf(element: T): Int = backing.indexOf(element)

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun lastIndexOf(element: T): Int = backing.lastIndexOf(element)

    override fun add(element: T): Boolean {
        val index = backing.size
        backing.add(element)
        operations.add(TrackedOperation.Add(index, 1))
        return true
    }

    override fun add(index: Int, element: T) {
        backing.add(index, element)
        operations.add(TrackedOperation.Add(index, 1))
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false
        backing.addAll(index, elements)
        operations.add(TrackedOperation.Add(index, elements.size))
        return true
    }

    override fun addAll(elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false
        val index = backing.size
        backing.addAll(elements)
        operations.add(TrackedOperation.Add(index, elements.size))
        return true
    }

    override fun clear() {
        if (backing.isEmpty()) return
        val count = backing.size
        backing.clear()
        operations.add(TrackedOperation.Remove(0, count))
    }

    override fun remove(element: T): Boolean {
        val index = backing.indexOf(element)
        if (index == -1) return false
        backing.removeAt(index)
        operations.add(TrackedOperation.Remove(index, 1))
        return true
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        var modified = false
        for (element in elements) {
            if (remove(element)) {
                modified = true
            }
        }
        return modified
    }

    override fun removeAt(index: Int): T {
        val removed = backing.removeAt(index)
        operations.add(TrackedOperation.Remove(index, 1))
        return removed
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        // Membership must remain fixed if elements is a live view of this list.
        val retainedValues = elements.toSet()
        var modified = false
        val iterator = backing.iterator()
        var index = 0
        while (iterator.hasNext()) {
            if (iterator.next() !in retainedValues) {
                iterator.remove()
                operations.add(TrackedOperation.Remove(index, 1))
                modified = true
            } else {
                index++
            }
        }
        return modified
    }

    override fun set(index: Int, element: T): T {
        val old = backing.set(index, element)
        operations.add(TrackedOperation.Update(index))
        return old
    }

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val item = backing.removeAt(fromIndex)
        backing.add(toIndex, item)
        operations.add(TrackedOperation.Move(fromIndex, toIndex))
    }

    fun toMutations(): List<Mutation> = operations.map { op ->
        when (op) {
            is TrackedOperation.Add -> Mutation.Insert(op.index, op.count)
            is TrackedOperation.Remove -> Mutation.Remove(op.index, op.count)
            is TrackedOperation.Update -> Mutation.Update(op.index)
            is TrackedOperation.Move -> Mutation.Move(op.fromIndex, op.toIndex)
        }
    }

    fun toList(): List<T> = backing.toList()
}

private sealed class TrackedOperation {
    data class Add(val index: Int, val count: Int) : TrackedOperation()
    data class Remove(val index: Int, val count: Int) : TrackedOperation()
    data class Update(val index: Int) : TrackedOperation()
    data class Move(val fromIndex: Int, val toIndex: Int) : TrackedOperation()
}

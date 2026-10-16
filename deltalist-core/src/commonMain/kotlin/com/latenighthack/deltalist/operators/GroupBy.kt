package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.flow.flow

/**
 * Groups items into sections by a key selector.
 * The key becomes the section header.
 * Only the contiguous loaded prefix is grouped: the first unloaded or absent position
 * ends the projection, even if later positions are loaded. No pages are requested.
 * Use fully loaded input for authoritative groups/counts, or treat this as a partial view.
 * Group domain values before expensive lazy row construction.
 *
 * Upstream mutations are followed into the groups, one [SectionedChange] per emission:
 * - Groups appearing or disappearing, while every other group keeps its items and order,
 *   emit [SectionedChange.Sections] with [SectionMutation.Remove] and [SectionMutation.Insert].
 * - Item changes confined to one group, with the group keys unchanged, emit
 *   [SectionedChange.Items] in that section's coordinates.
 * - An upstream change that leaves every group as it was emits an empty [SectionedChange.Sections].
 * - Anything else emits [SectionedChange.Reload]: an upstream reload, groups changing order,
 *   edits in several groups, or groups appearing while another group's items change.
 */
fun <T, K> DeltaList<T>.groupBy(
    keySelector: (T) -> K
): SectionedDeltaList<K, T> = flow {
    val tracker = GroupTracker(keySelector)
    collect { delta ->
        val groups = tracker.group(delta)
        val sections = groups.map { Section(it.key, it.items) }
        emit(SectionedDelta(sections, tracker.change(delta, groups, headersChanged = emptyList())))
    }
}

/**
 * Groups the contiguous loaded prefix into sections with custom section header data.
 * [headerMapper] sees only that prefix's group, not the complete paginated collection.
 * Stops at the first unloaded/absent position and never requests pages.
 *
 * Changes follow the same rules as the key-only [groupBy]. A group whose header changed
 * is delivered as [SectionMutation.Update] when its items did not change; a header change
 * together with item changes is delivered as [SectionedChange.Reload].
 */
fun <T, K, S> DeltaList<T>.groupBy(
    keySelector: (T) -> K,
    headerMapper: (K, List<T>) -> S
): SectionedDeltaList<S, T> = flow {
    val tracker = GroupTracker(keySelector)
    var previousHeaders = emptyMap<K, S>()
    collect { delta ->
        val groups = tracker.group(delta)
        val sections = groups.map { Section(headerMapper(it.key, it.items), it.items) }
        val headersChanged = groups.indices.filter { index ->
            val key = groups[index].key
            key in previousHeaders && previousHeaders[key] != sections[index].header
        }
        previousHeaders = groups.indices.associate { groups[it].key to sections[it].header }
        emit(SectionedDelta(sections, tracker.change(delta, groups, headersChanged)))
    }
}

private class Group<K, T>(val key: K, val items: List<T>, val positions: List<Int>)

/**
 * Remembers the previous emission's loaded prefix so upstream mutations can be followed into
 * the groups. Positions are matched by identity through the running-coordinate mutations, never
 * by item equality.
 */
private class GroupTracker<T, K>(private val keySelector: (T) -> K) {
    private var items: List<T> = emptyList()
    private var keys: List<K> = emptyList()
    private var groups: List<Group<K, T>> = emptyList()
    private var size = 0
    private var hasBaseline = false

    private var nextItems: List<T> = emptyList()
    private var nextKeys: List<K> = emptyList()

    fun group(delta: Delta<T>): List<Group<K, T>> {
        nextItems = delta.items.softLoadedItems()
        nextKeys = nextItems.map(keySelector)
        val byKey = linkedMapOf<K, Pair<MutableList<T>, MutableList<Int>>>()
        nextItems.forEachIndexed { position, item ->
            val (members, positions) = byKey.getOrPut(nextKeys[position]) { mutableListOf<T>() to mutableListOf() }
            members += item
            positions += position
        }
        return byKey.map { (key, value) -> Group(key, value.first, value.second) }
    }

    /** The change from the previous emission to [next], then makes [next] the baseline. */
    fun change(delta: Delta<T>, next: List<Group<K, T>>, headersChanged: List<Int>): SectionedChange {
        val change = delta.change
        val result = if (hasBaseline && change is Change.Mutations) {
            derive(change.operations, delta.items.size, next, headersChanged) ?: SectionedChange.Reload
        } else SectionedChange.Reload
        items = nextItems
        keys = nextKeys
        groups = next
        size = delta.items.size
        hasBaseline = true
        return result
    }

    private fun derive(
        operations: List<Mutation>,
        newSize: Int,
        next: List<Group<K, T>>,
        headersChanged: List<Int>,
    ): SectionedChange? {
        val followed = PrefixSlots.follow(items.size, size, operations) ?: return null
        if (followed.size != newSize) return null

        // The previous prefix position at each new prefix position, or -1 for a value that
        // entered the projection (inserted, loaded, or regrouped).
        val origin = IntArray(nextItems.size) { position ->
            val old = followed.slotAt(position)
            if (old >= 0 && keys[old] == nextKeys[position]) old else -1
        }
        val updated = mutableSetOf<Int>()
        for ((position, old) in origin.withIndex()) {
            if (old >= 0 && (old in followed.updated || items[old] != nextItems[position])) updated += old
        }

        val oldOrder = groups.map { it.key }
        val newOrder = next.map { it.key }
        val oldIndex = oldOrder.withIndex().associate { it.value to it.index }
        val edits = mutableMapOf<Int, List<Mutation>>()
        for ((index, group) in next.withIndex()) {
            val previous = oldIndex[group.key]?.let { groups[it] } ?: continue
            val initial = previous.positions
            var inserted = -1
            val final = group.positions.map { position -> origin[position].takeIf { it >= 0 } ?: inserted-- }
            val mutations = slotMutations(initial, final, updated)
            if (mutations.isNotEmpty()) edits[index] = mutations
        }

        if (oldOrder == newOrder) {
            return when {
                edits.isEmpty() -> SectionedChange.Sections(headersChanged.map { SectionMutation.Update(it) })
                edits.size == 1 && headersChanged.isEmpty() -> edits.entries.single().let { (section, mutations) ->
                    SectionedChange.Items(section, mutations)
                }
                else -> null
            }
        }

        // Groups came or went. Every surviving group must be untouched and keep its order.
        val newKeys = newOrder.toSet()
        val survivors = oldOrder.filter { it in newKeys }
        if (edits.isNotEmpty() || survivors != newOrder.filter { it in oldIndex }) return null
        val mutations = mutableListOf<SectionMutation>()
        var running = 0
        for (key in oldOrder) {
            if (key in newKeys) running++ else mutations += SectionMutation.Remove(running)
        }
        newOrder.forEachIndexed { index, key -> if (key !in oldIndex) mutations += SectionMutation.Insert(index) }
        headersChanged.forEach { mutations += SectionMutation.Update(it) }
        return SectionedChange.Sections(mutations.coalesced())
    }
}

/** Joins adjacent single-section inserts and removes, keeping running coordinates. */
private fun List<SectionMutation>.coalesced(): List<SectionMutation> {
    val result = mutableListOf<SectionMutation>()
    for (mutation in this) {
        val last = result.lastOrNull()
        result += when {
            last is SectionMutation.Remove && mutation is SectionMutation.Remove && mutation.index == last.index ->
                SectionMutation.Remove(last.index, last.count + mutation.count).also { result.removeAt(result.lastIndex) }
            last is SectionMutation.Insert && mutation is SectionMutation.Insert && mutation.index == last.index + last.count ->
                SectionMutation.Insert(last.index, last.count + mutation.count).also { result.removeAt(result.lastIndex) }
            else -> mutation
        }
    }
    return result
}

/**
 * Follows the previous loaded prefix through running-coordinate mutations of the whole list.
 * Only prefix positions are tracked; positions after the last tracked one are counted, so an
 * estimated size never allocates a slot per position.
 */
private class PrefixSlots private constructor(
    private val slots: MutableList<Int>,
    private var tail: Long,
    val updated: MutableSet<Int>,
) {
    val size: Int get() = (slots.size + tail).toInt()

    /** The previous prefix position now at [position], or -1. */
    fun slotAt(position: Int): Int = slots.getOrElse(position) { OTHER }

    private fun expand(to: Int) {
        val missing = to - slots.size
        if (missing <= 0) return
        repeat(missing) { slots += OTHER }
        tail -= missing
    }

    private fun trim() {
        var count = 0
        while (slots.isNotEmpty() && slots.last() == OTHER) {
            slots.removeAt(slots.lastIndex)
            count++
        }
        tail += count
    }

    private fun apply(operation: Mutation): Boolean {
        val total = slots.size + tail
        when (operation) {
            is Mutation.Insert -> {
                if (operation.count < 0 || operation.index.toLong() !in 0..total) return false
                if (operation.index >= slots.size) tail += operation.count
                else repeat(operation.count) { slots.add(operation.index, OTHER) }
            }
            is Mutation.Remove -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > total) return false
                val tracked = (minOf(slots.size, operation.index + operation.count) - operation.index).coerceAtLeast(0)
                repeat(tracked) { slots.removeAt(operation.index) }
                tail -= operation.count - tracked
            }
            is Mutation.Update -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > total) return false
                for (position in operation.index until minOf(slots.size, operation.index + operation.count)) {
                    if (slots[position] != OTHER) updated += slots[position]
                }
            }
            is Mutation.Move -> {
                if (operation.count < 0 || operation.fromIndex < 0 || operation.toIndex < 0 ||
                    operation.fromIndex.toLong() + operation.count > total ||
                    operation.toIndex.toLong() + operation.count > total) return false
                repeat(operation.count) { offset ->
                    val from = operation.fromIndex + offset
                    val to = operation.toIndex + offset
                    expand(maxOf(from, to) + 1)
                    slots.add(to, slots.removeAt(from))
                }
            }
        }
        trim()
        return true
    }

    companion object {
        private const val OTHER = -1

        fun follow(prefix: Int, size: Int, operations: List<Mutation>): PrefixSlots? {
            val slots = PrefixSlots((0 until prefix).toMutableList(), (size - prefix).toLong(), mutableSetOf())
            return slots.takeIf { operations.all(slots::apply) }
        }
    }
}

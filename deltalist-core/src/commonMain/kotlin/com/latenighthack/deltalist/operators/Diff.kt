package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Convenience method for self-keyed items
 */
fun <T> Flow<List<T>>.asDeltaFlow(): DeltaFlow<T> = asDeltaFlow { it }

/**
 * Converts a Flow<List<T>> to a DeltaFlow<T> by automatically computing the
 * difference between consecutive list emissions.
 *
 * The [idSelector] function extracts a stable identifier from each item.
 * Items with the same ID but different content (via equals) generate Update mutations.
 * Items that change position generate Move mutations.
 *
 * The first emission is always a Reload. Subsequent emissions compute the minimal
 * set of mutations (Insert, Remove, Update, Move) to transform the previous list
 * into the new list.
 *
 * Example:
 * ```
 * data class Contact(val contactId: String, val name: String, val phone: String)
 *
 * val contactsFlow: Flow<List<Contact>> = ...
 * val deltaFlow: DeltaFlow<Contact> = contactsFlow.asDeltaFlow { it.contactId }
 * ```
 */
fun <T, ID> Flow<List<T>>.asDeltaFlow(idSelector: (T) -> ID): DeltaFlow<T> = flow {
    var previousItems: List<T>? = null

    collect { currentItems ->
        val prev = previousItems
        if (prev == null) {
            // First emission - always reload
            emit(Delta(currentItems, Change.Reload))
        } else {
            val mutations = computeDiff(prev, currentItems, idSelector)
            if (mutations.isEmpty()) {
                // No changes detected, but still emit for consistency
                // (The list reference may have changed even if content is same)
                emit(Delta(currentItems, Change.Mutations(emptyList())))
            } else {
                emit(Delta(currentItems, Change.Mutations(mutations)))
            }
        }
        previousItems = currentItems
    }
}

/**
 * Computes the mutations needed to transform [oldList] into [newList].
 *
 * Algorithm overview:
 * 1. Build maps of ID -> (index, item) for both lists
 * 2. Identify removed items (in old, not in new)
 * 3. Identify inserted items (in new, not in old)
 * 4. Identify moved items (same ID, different position after removals/insertions)
 * 5. Identify updated items (same ID, different content)
 *
 * The mutations are generated in an order that maintains valid indices:
 * - Removes are applied from highest to lowest index
 * - Inserts are applied from lowest to highest index
 * - Moves are computed on the intermediate state
 * - Updates are applied last
 */
private fun <T, ID> computeDiff(
    oldList: List<T>,
    newList: List<T>,
    idSelector: (T) -> ID
): List<Mutation> {
    if (oldList.isEmpty() && newList.isEmpty()) {
        return emptyList()
    }

    // Build ID -> (index, item) maps
    val oldById = oldList.withIndex().associate { (index, item) -> idSelector(item) to IndexedItem(index, item) }
    val newById = newList.withIndex().associate { (index, item) -> idSelector(item) to IndexedItem(index, item) }

    val oldIds = oldById.keys
    val newIds = newById.keys

    // Identify removals and insertions
    val removedIds = oldIds - newIds
    val insertedIds = newIds - oldIds
    val commonIds = oldIds.intersect(newIds)

    // Simulate the mutations to track index changes
    val mutations = mutableListOf<Mutation>()

    // Working state: maps current position to ID
    // We'll track IDs and their positions as we apply mutations
    val workingIds = oldList.map { idSelector(it) }.toMutableList()

    // Step 1: Process removals (highest index first to avoid shifting issues)
    val removalsInOrder = removedIds
        .mapNotNull { id -> oldById[id]?.let { id to it.index } }
        .sortedByDescending { it.second }

    for ((id, _) in removalsInOrder) {
        val currentIndex = workingIds.indexOf(id)
        if (currentIndex >= 0) {
            mutations.add(Mutation.Remove(currentIndex, 1))
            workingIds.removeAt(currentIndex)
        }
    }

    // Step 2: Process insertions (in order of target position)
    val insertionsInOrder = insertedIds
        .mapNotNull { id -> newById[id]?.let { id to it.index } }
        .sortedBy { it.second }

    for ((id, targetIndex) in insertionsInOrder) {
        // Insert at the target position (clamped to valid range)
        val insertIndex = minOf(targetIndex, workingIds.size)
        mutations.add(Mutation.Insert(insertIndex, 1))
        workingIds.add(insertIndex, id)
    }

    // Step 3: Process moves
    // At this point, workingIds has all the same IDs as newList, but possibly in different order
    // We need to sort them to match newList order

    // Build target order
    val targetOrder = newList.map { idSelector(it) }

    // Move items to their correct positions
    for (targetIndex in targetOrder.indices) {
        val expectedId = targetOrder[targetIndex]
        val currentIndex = workingIds.indexOf(expectedId)

        if (currentIndex != targetIndex && currentIndex >= 0) {
            mutations.add(Mutation.Move(currentIndex, targetIndex, 1))
            val movedId = workingIds.removeAt(currentIndex)
            workingIds.add(targetIndex, movedId)
        }
    }

    // Step 4: Process updates (items with same ID but different content)
    // At this point, indices should match between workingIds and targetOrder
    for ((id, newIndexedItem) in newById) {
        if (id in commonIds) {
            val oldItem = oldById[id]?.item
            val newItem = newIndexedItem.item

            // Check if content changed (using equals)
            if (oldItem != newItem) {
                val currentIndex = workingIds.indexOf(id)
                if (currentIndex >= 0) {
                    mutations.add(Mutation.Update(currentIndex, 1))
                }
            }
        }
    }

    return coalesceMutations(mutations)
}

private data class IndexedItem<T>(val index: Int, val item: T)

/**
 * Coalesces consecutive mutations of the same type into single mutations with count > 1.
 */
private fun coalesceMutations(mutations: List<Mutation>): List<Mutation> {
    if (mutations.size <= 1) return mutations

    val result = mutableListOf<Mutation>()
    var i = 0

    while (i < mutations.size) {
        val current = mutations[i]

        when (current) {
            is Mutation.Insert -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    if (next is Mutation.Insert && next.index == current.index + count) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Insert(current.index, count))
                i = j
            }
            is Mutation.Remove -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    // Consecutive removes at the same index (since items shift down)
                    if (next is Mutation.Remove && next.index == current.index) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Remove(current.index, count))
                i = j
            }
            is Mutation.Update -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    if (next is Mutation.Update && next.index == current.index + count) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Update(current.index, count))
                i = j
            }
            is Mutation.Move -> {
                result.add(current)
                i++
            }
        }
    }

    return result
}

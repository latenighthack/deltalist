package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.asSoftList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Lifts a single, already-loaded list into a one-shot [DeltaList]: emits exactly one
 * [Delta] carrying the whole list as a [Change.Reload].
 *
 * This is the in-memory on-ramp for static data. There is nothing to diff against (a
 * single snapshot), so no id selector is needed; apply [com.latenighthack.deltalist.operators.withStableIds]
 * downstream if you need stable keys.
 */
fun <T> List<T>.toDeltaList(): DeltaList<T> = flowOf(Delta(asSoftList(), Change.Reload))

/**
 * Convenience method for self-keyed items
 */
fun <T> Flow<List<T>>.asDeltaList(): DeltaList<T> = asDeltaList { it }

/**
 * Converts a Flow<List<T>> to a DeltaList<T> by automatically computing the
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
 * val deltaList: DeltaList<Contact> = contactsFlow.asDeltaList { it.contactId }
 * ```
 */
fun <T, ID> Flow<List<T>>.asDeltaList(idSelector: (T) -> ID): DeltaList<T> = flow {
    var previousItems: List<T>? = null

    collect { currentItems ->
        val prev = previousItems
        val change = if (prev == null) {
            // First emission - always reload
            Change.Reload
        } else {
            // computeDiff returns null when the idSelector is non-injective (duplicate
            // ids) and a safe minimal diff can't be guaranteed; fall back to a Reload.
            computeDiff(prev, currentItems, idSelector)
                ?.let { Change.Mutations(it) }
                ?: Change.Reload
        }
        emit(Delta(currentItems.asSoftList(), change))
        previousItems = currentItems
    }
}

/**
 * Computes the mutations needed to transform [oldList] into [newList].
 *
 * Returns `null` when [idSelector] is non-injective (the same id appears more than
 * once in either list). A correct minimal diff can't be guaranteed in that case, so
 * callers should fall back to [Change.Reload].
 *
 * The operations honor the contract documented on [applyChange]: they are applied
 * **sequentially** in running (post-prior-op) coordinates, and are emitted
 * left-to-right so that after each operation the running list's prefix already
 * matches [newList] up to that index. The phases are:
 * 1. Remove every id that is absent from [newList] (left-to-right; consecutive
 *    removals coalesce).
 * 2. Walk target positions `0..newList.size-1`; at each position either keep the
 *    item already there, [Mutation.Insert] a newly-introduced item, or
 *    [Mutation.Move] an existing item into place.
 * 3. [Mutation.Update] any retained item whose content changed.
 *
 * Items that are already in the correct relative order are never moved, so pure
 * insert / remove / append / prepend changes emit zero moves.
 */
private fun <T, ID> computeDiff(
    oldList: List<T>,
    newList: List<T>,
    idSelector: (T) -> ID
): List<Mutation>? {
    if (oldList.isEmpty() && newList.isEmpty()) {
        return emptyList()
    }

    val oldIds = oldList.map(idSelector)
    val newIds = newList.map(idSelector)

    // Reject non-injective id selectors: a stable diff is only well-defined when ids
    // are unique within each snapshot. The caller falls back to a Reload.
    val oldIdSet = oldIds.toHashSet()
    val newIdSet = newIds.toHashSet()
    if (oldIdSet.size != oldIds.size || newIdSet.size != newIds.size) {
        return null
    }

    val newIndexById = HashMap<ID, Int>(newIds.size)
    for ((index, id) in newIds.withIndex()) newIndexById[id] = index

    val mutations = mutableListOf<Mutation>()

    // Running list of ids; transformed in place to match newIds.
    val working = oldIds.toMutableList()

    // Phase 1: remove ids absent from newList (left-to-right; running index).
    var i = 0
    while (i < working.size) {
        if (working[i] !in newIdSet) {
            mutations.add(Mutation.Remove(i, 1))
            working.removeAt(i)
        } else {
            i++
        }
    }

    // Phase 2: build toward newList left-to-right. After handling index t,
    // working[0..t] == newIds[0..t], so working still holds every retained id.
    for (t in newIds.indices) {
        val desired = newIds[t]
        if (t < working.size && working[t] == desired) {
            continue
        }
        if (desired in oldIdSet) {
            // Retained id currently sitting further along; move it into place.
            val from = working.indexOf(desired)
            mutations.add(Mutation.Move(from, t, 1))
            working.removeAt(from)
            working.add(t, desired)
        } else {
            // Brand-new id; insert it.
            mutations.add(Mutation.Insert(t, 1))
            working.add(t, desired)
        }
    }

    // Phase 3: content updates for retained items (ids in both lists).
    val oldItemById = HashMap<ID, T>(oldIds.size)
    for (item in oldList) oldItemById[idSelector(item)] = item
    for (t in newList.indices) {
        val newItem = newList[t]
        val id = newIds[t]
        val oldItem = oldItemById[id]
        if (id in oldIdSet && oldItem != newItem) {
            mutations.add(Mutation.Update(t, 1))
        }
    }

    return coalesceMutations(mutations)
}

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

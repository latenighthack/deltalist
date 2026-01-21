package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.flow.flow

/**
 * Groups items into sections by a key selector.
 * The key becomes the section header.
 */
fun <T, K> DeltaList<T>.groupBy(
    keySelector: (T) -> K
): SectionedDeltaList<K, T> = flow {
    var previousGroups: Map<K, List<T>> = emptyMap()
    var previousKeyOrder: List<K> = emptyList()

    collect { delta ->
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in delta.items) {
            val key = keySelector(item)
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }

        val keyOrder = groups.keys.toList()
        val sections = keyOrder.map { key ->
            Section(key, groups[key] ?: emptyList())
        }

        val change = when (delta.change) {
            is Change.Reload -> SectionedChange.Reload
            is Change.Mutations -> {
                val sectionChanges = computeGroupChanges(
                    previousKeyOrder,
                    previousGroups,
                    keyOrder,
                    groups
                )
                sectionChanges ?: SectionedChange.Reload
            }
        }

        previousGroups = groups
        previousKeyOrder = keyOrder

        emit(SectionedDelta(sections, change))
    }
}

/**
 * Groups items into sections with custom section header data.
 */
fun <T, K, S> DeltaList<T>.groupBy(
    keySelector: (T) -> K,
    headerMapper: (K, List<T>) -> S
): SectionedDeltaList<S, T> = flow {
    var previousGroups: Map<K, List<T>> = emptyMap()
    var previousKeyOrder: List<K> = emptyList()

    collect { delta ->
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in delta.items) {
            val key = keySelector(item)
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }

        val keyOrder = groups.keys.toList()
        val sections = keyOrder.map { key ->
            val items = groups[key] ?: emptyList()
            Section(headerMapper(key, items), items)
        }

        val change = when (delta.change) {
            is Change.Reload -> SectionedChange.Reload
            is Change.Mutations -> {
                val sectionChanges = computeGroupChanges(
                    previousKeyOrder,
                    previousGroups,
                    keyOrder,
                    groups
                )
                sectionChanges ?: SectionedChange.Reload
            }
        }

        previousGroups = groups
        previousKeyOrder = keyOrder

        emit(SectionedDelta(sections, change))
    }
}

/**
 * Compute section-level changes when items are grouped.
 * Returns null if changes are too complex to track.
 */
private fun <K, T> computeGroupChanges(
    previousKeyOrder: List<K>,
    previousGroups: Map<K, List<T>>,
    newKeyOrder: List<K>,
    newGroups: Map<K, List<T>>
): SectionedChange? {
    if (previousKeyOrder != newKeyOrder) {
        return null
    }

    val itemChanges = mutableListOf<Pair<Int, List<Mutation>>>()

    for ((index, key) in newKeyOrder.withIndex()) {
        val previousItems = previousGroups[key] ?: emptyList()
        val newItems = newGroups[key] ?: emptyList()

        if (previousItems != newItems) {
            if (previousItems.size != newItems.size) {
                return null
            }
            val updates = previousItems.indices.filter { i ->
                previousItems[i] != newItems[i]
            }
            if (updates.isNotEmpty()) {
                itemChanges.add(index to updates.map { Mutation.Update(it) })
            }
        }
    }

    return when {
        itemChanges.isEmpty() -> SectionedChange.Reload
        itemChanges.size == 1 -> {
            val (sectionIndex, mutations) = itemChanges[0]
            SectionedChange.Items(sectionIndex, mutations)
        }
        else -> SectionedChange.Reload
    }
}

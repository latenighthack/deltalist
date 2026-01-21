package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlinx.coroutines.flow.*

/**
 * Concatenates multiple delta flows into a single delta flow.
 * Each flow becomes a logical "section" for mutation tracking purposes,
 * but the result is a flat list.
 */
fun <T> concatSections(vararg flows: DeltaList<T>): DeltaList<T> =
    concatSections(flows.toList())

fun <T> concatSections(flows: List<DeltaList<T>>): DeltaList<T> {
    if (flows.isEmpty()) return flowOf(Delta(emptyList(), Change.Reload))
    if (flows.size == 1) return flows[0]

    return combine(flows) { deltas ->
        val combinedItems = ConcatenatedMultiList(deltas.map { it.items })

        val anyReload = deltas.any { it.change is Change.Reload }
        if (anyReload) {
            return@combine Delta(combinedItems, Change.Reload)
        }

        val allMutations = mutableListOf<Mutation>()

        for ((index, delta) in deltas.withIndex()) {
            val offset = deltas.take(index).sumOf { it.items.size }

            when (val change = delta.change) {
                is Change.Reload -> { }
                is Change.Mutations -> {
                    for (mutation in change.operations) {
                        val translated = when (mutation) {
                            is Mutation.Insert -> Mutation.Insert(offset + mutation.index, mutation.count)
                            is Mutation.Remove -> Mutation.Remove(offset + mutation.index, mutation.count)
                            is Mutation.Update -> Mutation.Update(offset + mutation.index, mutation.count)
                            is Mutation.Move -> Mutation.Move(
                                offset + mutation.fromIndex,
                                offset + mutation.toIndex,
                                mutation.count
                            )
                        }
                        allMutations.add(translated)
                    }
                }
            }
        }

        val change = if (allMutations.isEmpty()) Change.Reload else Change.Mutations(allMutations)
        Delta(combinedItems, change)
    }
}

/**
 * Extension to concatenate a list of delta flows.
 */
fun <T> List<DeltaList<T>>.concat(): DeltaList<T> = concatSections(this)

/**
 * Lazy list that concatenates multiple lists.
 */
internal class ConcatenatedMultiList<T>(
    private val lists: List<List<T>>
) : AbstractList<T>() {
    override val size: Int = lists.sumOf { it.size }

    override fun get(index: Int): T {
        var remaining = index
        for (list in lists) {
            if (remaining < list.size) return list[remaining]
            remaining -= list.size
        }
        throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
    }
}

/**
 * Creates a sectioned delta flow from multiple delta flows.
 * Each input flow becomes a section with the given header.
 */
fun <S, T> sectionedDeltaList(
    vararg sections: Pair<S, DeltaList<T>>
): SectionedDeltaList<S, T> = sectionedDeltaList(sections.toList())

fun <S, T> sectionedDeltaList(
    sections: List<Pair<S, DeltaList<T>>>
): SectionedDeltaList<S, T> {
    if (sections.isEmpty()) {
        return flowOf(SectionedDelta(emptyList(), SectionedChange.Reload))
    }

    val flows = sections.map { (header, itemFlow) ->
        itemFlow.map { delta -> header to delta }
    }

    return combine(flows) { headerDeltaPairs ->
        val sectionList = headerDeltaPairs.map { (header, delta) ->
            Section(header, delta.items)
        }

        val anyReload = headerDeltaPairs.any { (_, delta) -> delta.change is Change.Reload }
        if (anyReload) {
            return@combine SectionedDelta(sectionList, SectionedChange.Reload)
        }

        // Find sections with item changes
        val itemChanges = headerDeltaPairs.mapIndexedNotNull { index, (_, delta) ->
            when (val change = delta.change) {
                is Change.Reload -> null
                is Change.Mutations -> {
                    if (change.operations.isNotEmpty()) index to change.operations else null
                }
            }
        }

        val change = when {
            itemChanges.isEmpty() -> SectionedChange.Reload
            itemChanges.size == 1 -> {
                val (sectionIndex, mutations) = itemChanges[0]
                SectionedChange.Items(sectionIndex, mutations)
            }
            else -> {
                // Multiple sections changed - emit reload for simplicity
                // Could be optimized to emit multiple Items changes
                SectionedChange.Reload
            }
        }

        SectionedDelta(sectionList, change)
    }
}

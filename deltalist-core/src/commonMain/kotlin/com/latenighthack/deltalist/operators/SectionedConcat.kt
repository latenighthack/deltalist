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
    if (flows.isEmpty()) return flowOf(Delta(emptyList<T>().asSoftList(), Change.Reload))
    if (flows.size == 1) return flows[0]

    return flow {
        // Per-source previous emissions: under `combine`, only the source whose Delta reference
        // changed actually emitted this tick; the rest carry stale changes that must not be replayed.
        val lifetime = CompositionLifetime()
        var prevDeltas: Array<Delta<T>>? = null
        var prevCombinedLoaded: List<T>? = null

        emitAll(
            combine(flows) { deltas ->
                val sources = deltas.map { it.items }
                val combinedItems = lifecycleList(ConcatenatedMultiList(sources), sources, lifetime.next()) {
                    concatenatedRoute(sources, it)
                }
                val newLoaded = combinedItems.softLoadedItems()

                val previous = prevDeltas
                val fullyLoaded = newLoaded.size == combinedItems.size

                val emitterReloaded = deltas.withIndex().any { (i, d) ->
                    (previous == null || d !== previous[i]) && d.change is Change.Reload
                }

                val change: Change = if (previous == null || emitterReloaded || !fullyLoaded) {
                    Change.Reload
                } else {
                    val ops = mutableListOf<Mutation>()
                    for ((index, delta) in deltas.withIndex()) {
                        val emitted = delta !== previous[index]
                        val mutations = delta.change as? Change.Mutations
                        if (emitted && mutations != null) {
                            val offset = deltas.take(index).sumOf { it.items.size }
                            mutations.operations.forEach { ops += it.offsetBy(offset) }
                        }
                    }
                    val prev = prevCombinedLoaded
                    if (ops.isEmpty() || prev == null) {
                        Change.Reload
                    } else {
                        val rebuilt = runCatching { applyChange(prev, Change.Mutations(ops), newLoaded) }.getOrNull()
                        if (rebuilt == newLoaded) Change.Mutations(ops) else Change.Reload
                    }
                }

                prevDeltas = deltas.copyOf()
                prevCombinedLoaded = newLoaded
                Delta(combinedItems, change)
            }.onCompletion { cause -> if (cause != null) lifetime.close() }
        )
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
    private val lists: List<SoftList<T>>
) : AbstractSoftList<T>() {
    override val size: Int = lists.sumOf { it.size }

    override fun softGet(index: Int): SoftValue<T>? {
        if (index < 0 || index >= size) return null
        var remaining = index
        for (list in lists) {
            if (remaining < list.size) return list.softGet(remaining)
            remaining -= list.size
        }
        return null
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

    return flow {
        // Only the section whose Delta reference changed this tick actually emitted; stale changes
        // on the other sections must be ignored, otherwise the operator over-reloads (or worse,
        // attributes a change to the wrong section) once several sections have each mutated.
        var prevDeltas: Array<Delta<T>>? = null

        emitAll(
            combine(flows) { headerDeltaPairs ->
                val sectionList = headerDeltaPairs.map { (header, delta) ->
                    Section(header, delta.items)
                }

                val previous = prevDeltas
                val emitterReloaded = headerDeltaPairs.withIndex().any { (i, pair) ->
                    (previous == null || pair.second !== previous[i]) && pair.second.change is Change.Reload
                }

                val change = if (previous == null || emitterReloaded) {
                    SectionedChange.Reload
                } else {
                    // Item changes from sections that actually emitted this tick.
                    val itemChanges = headerDeltaPairs.mapIndexedNotNull { index, (_, delta) ->
                        val emitted = delta !== previous[index]
                        val mutations = delta.change as? Change.Mutations
                        if (emitted && mutations != null && mutations.operations.isNotEmpty()) {
                            index to mutations.operations
                        } else null
                    }
                    when {
                        itemChanges.isEmpty() -> SectionedChange.Reload
                        itemChanges.size == 1 -> {
                            val (sectionIndex, mutations) = itemChanges[0]
                            SectionedChange.Items(sectionIndex, mutations)
                        }
                        // Multiple sections genuinely changed in one tick - reload for simplicity.
                        else -> SectionedChange.Reload
                    }
                }

                prevDeltas = Array(headerDeltaPairs.size) { headerDeltaPairs[it].second }
                SectionedDelta(sectionList, change)
            }
        )
    }
}

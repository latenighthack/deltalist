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

    val flows = sections.map { (_, itemFlow) -> itemFlow.withEmissionSequence() }

    return flow {
        var previous: Array<SequencedDelta<T>>? = null
        emitAll(
            combine(flows) { emissions ->
                val sectionList = emissions.mapIndexed { index, emission ->
                    Section(sections[index].first, emission.delta.items)
                }
                val old = previous
                // combine may consume several values from one child before invoking this
                // transform. A latest mutation is only valid after its immediate predecessor.
                val needsReload = old == null || emissions.withIndex().any { (index, emission) ->
                    val prior = old[index]
                    emission.sequence != prior.sequence &&
                        (emission.sequence != prior.sequence + 1 || emission.delta.change is Change.Reload)
                }
                val change = if (needsReload) {
                    SectionedChange.Reload
                } else {
                    val itemChanges = emissions.mapIndexedNotNull { index, emission ->
                        val mutations = emission.delta.change as? Change.Mutations
                        if (emission.sequence != old!![index].sequence && mutations != null && mutations.operations.isNotEmpty()) {
                            index to mutations.operations
                        } else null
                    }
                    if (itemChanges.size == 1) {
                        val (index, mutations) = itemChanges.single()
                        SectionedChange.Items(index, mutations)
                    } else SectionedChange.Reload
                }
                previous = emissions.copyOf()
                SectionedDelta(sectionList, change)
            }
        )
    }
}

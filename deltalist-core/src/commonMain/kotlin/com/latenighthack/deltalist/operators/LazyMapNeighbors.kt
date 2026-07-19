package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.flow

/**
 * Like [deferredMap] but the transform also sees the previous item, e.g. for chat
 * bubble grouping where an item's rendering depends on who sent the item before it.
 *
 * See [lazyMapPreviousNext] for the change-rewriting contract.
 */
fun <T, R> DeltaList<T>.lazyMapPrevious(transform: (current: T, previous: T?) -> R): DeltaList<R> =
    lazyMapPreviousNext { current, previous, _ -> transform(current, previous) }

/**
 * Like [deferredMap] but the transform also sees the previous and next items.
 *
 * Because an item's transformed value depends on its neighbors, a mutation at index i
 * silently changes the *values* at i-1/i+1 even though the source diff carries no
 * operation for them. A binder that only rebinds mutated indices would render stale
 * neighbors, so this operator appends [Mutation.Update]s (in final coordinates, after
 * all source operations) for every index whose neighbor window was touched. Values are
 * computed on access from the emitted snapshot (no caching), so the appended updates
 * re-source the correct value per the [com.latenighthack.deltalist.applyChange] contract.
 */
fun <T, R> DeltaList<T>.lazyMapPreviousNext(transform: (current: T, previous: T?, next: T?) -> R): DeltaList<R> = flow {
    collect { delta ->
        emit(
            Delta(
                items = NeighborMapList(delta.items, transform),
                change = delta.change.withNeighborUpdates(delta.items.size)
            )
        )
    }
}

internal class NeighborMapList<T, R>(
    private val source: SoftList<T>,
    private val transform: (T, T?, T?) -> R
) : AbstractSoftList<R>() {
    override val size: Int get() = source.size

    override fun softGet(index: Int): SoftValue<R>? =
        when (val soft = source.softGet(index)) {
            is SoftValue.Present -> {
                val previous = (source.softGet(index - 1) as? SoftValue.Present)?.value
                val next = (source.softGet(index + 1) as? SoftValue.Present)?.value
                SoftValue.Present(transform(soft.value, previous, next))
            }
            is SoftValue.NotLoaded -> soft
            null -> null
        }
}

private fun Change.withNeighborUpdates(finalSize: Int): Change {
    if (this !is Change.Mutations) return this

    val markers = mutableListOf<Int>()
    for (k in operations.indices) {
        var candidates = neighborSeeds(operations[k])
        for (j in k + 1 until operations.size) {
            candidates = candidates.mapNotNull { shiftThrough(it, operations[j]) }
        }
        markers += candidates
    }

    val updates = markers
        .filter { it in 0 until finalSize }
        .distinct()
        .sorted()
        .map { Mutation.Update(it) }

    return if (updates.isEmpty()) this else Change.Mutations(operations + updates)
}

/**
 * Indices (in the coordinate space immediately after [op] applies) whose transformed
 * value may have changed as a *side effect* of the operation. Inserted/updated items
 * themselves are re-sourced by the applier and need no extra update; moved items keep
 * their old values, so the landed block is included. A superset is fine — a redundant
 * update is just a harmless rebind.
 */
private fun neighborSeeds(op: Mutation): List<Int> = when (op) {
    is Mutation.Insert -> listOf(op.index - 1, op.index + op.count)
    is Mutation.Remove -> listOf(op.index - 1, op.index)
    is Mutation.Update -> listOf(op.index - 1, op.index + op.count)
    is Mutation.Move -> listOf(
        op.fromIndex - 1, op.fromIndex, op.fromIndex + op.count,
        op.toIndex - 1, op.toIndex + op.count
    ) + (op.toIndex until op.toIndex + op.count)
}

/** Translates a marker through a subsequent running-coordinate operation; null = removed. */
private fun shiftThrough(marker: Int, op: Mutation): Int? = when (op) {
    is Mutation.Insert -> if (marker >= op.index) marker + op.count else marker
    is Mutation.Remove -> when {
        marker >= op.index + op.count -> marker - op.count
        marker >= op.index -> null
        else -> marker
    }
    is Mutation.Update -> marker
    is Mutation.Move -> {
        if (marker in op.fromIndex until op.fromIndex + op.count) {
            marker - op.fromIndex + op.toIndex
        } else {
            val afterRemove = if (marker >= op.fromIndex + op.count) marker - op.count else marker
            if (afterRemove >= op.toIndex) afterRemove + op.count else afterRemove
        }
    }
}

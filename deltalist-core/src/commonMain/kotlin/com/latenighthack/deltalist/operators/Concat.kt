package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.applyChange
import com.latenighthack.deltalist.asSoftList
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Lazy list that concatenates two lists without accessing items until needed.
 * Implements [SoftList] to propagate soft access from either source list.
 */
internal class ConcatenatedList<T>(
    private val first: SoftList<T>,
    private val second: SoftList<T>
) : AbstractSoftList<T>() {
    override val size: Int get() = first.size + second.size

    override fun softGet(index: Int): SoftValue<T>? {
        if (index < 0 || index >= size) return null
        return if (index < first.size) first.softGet(index) else second.softGet(index - first.size)
    }
}

/**
 * Concatenates two delta streams. Because [combine] re-emits with the *latest* value of every
 * source whenever any one emits, the non-emitting source's `change` is **stale** and must not be
 * re-applied. We therefore identify which source actually emitted (by [Delta] reference identity —
 * each collection owns its history and its `combine` transform runs sequentially) and fold only the
 * emitter's mutations, offset into the combined coordinate space. A reconstruction guard then
 * verifies the candidate mutations actually rebuild the new snapshot from the previous one,
 * falling back to [Change.Reload] on any inconsistency (e.g. conflated/soft sources).
 */
fun <T> DeltaList<T>.concat(other: DeltaList<T>): DeltaList<T> {
    val upstream = this

    return flow {
        var prevFirst: Delta<T>? = null
        var prevSecond: Delta<T>? = null
        var prevCombinedLoaded: List<T>? = null

        emitAll(
            combine(upstream, other) { first, second ->
                val combinedItems = ConcatenatedList(first.items, second.items)
                val newLoaded = combinedItems.softLoadedItems()

                val isFirstTick = prevFirst == null && prevSecond == null
                val firstEmitted = first !== prevFirst
                val secondEmitted = second !== prevSecond
                val emitterReloaded =
                    (firstEmitted && first.change is Change.Reload) ||
                        (secondEmitted && second.change is Change.Reload)
                val fullyLoaded = newLoaded.size == combinedItems.size

                val change: Change = if (isFirstTick || emitterReloaded || !fullyLoaded) {
                    Change.Reload
                } else {
                    val ops = mutableListOf<Mutation>()
                    (first.change as? Change.Mutations)?.takeIf { firstEmitted }?.let { ops += it.operations }
                    (second.change as? Change.Mutations)?.takeIf { secondEmitted }
                        ?.let { ms -> ops += ms.operations.map { it.offsetBy(first.items.size) } }

                    val prev = prevCombinedLoaded
                    if (ops.isEmpty() || prev == null) {
                        Change.Reload
                    } else {
                        val rebuilt = runCatching { applyChange(prev, Change.Mutations(ops), newLoaded) }.getOrNull()
                        if (rebuilt == newLoaded) Change.Mutations(ops) else Change.Reload
                    }
                }

                prevFirst = first
                prevSecond = second
                prevCombinedLoaded = newLoaded

                Delta(combinedItems, change)
            }
        )
    }
}

/** Shifts a mutation's running coordinates by [offset] into a concatenated coordinate space. */
internal fun Mutation.offsetBy(offset: Int): Mutation = when (this) {
    is Mutation.Insert -> copy(index = index + offset)
    is Mutation.Remove -> copy(index = index + offset)
    is Mutation.Update -> copy(index = index + offset)
    is Mutation.Move -> copy(fromIndex = fromIndex + offset, toIndex = toIndex + offset)
}

fun <T> DeltaList<T>.header(item: T): DeltaList<T> {
    val headerFlow: DeltaList<T> = flowOf(Delta(listOf(item).asSoftList(), Change.Reload))
    return headerFlow.concat(this)
}

fun <T> DeltaList<T>.footer(item: T): DeltaList<T> {
    val footerFlow: DeltaList<T> = flowOf(Delta(listOf(item).asSoftList(), Change.Reload))
    return this.concat(footerFlow)
}

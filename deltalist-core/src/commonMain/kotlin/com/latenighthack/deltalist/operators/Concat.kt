package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.asSoftList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
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
 * Concatenates two delta streams without evaluating their items. Per-collection emission
 * sequences detect values skipped by combine: only consecutive changes can be forwarded.
 * Thus lazy row construction and identity survive ordinary incremental changes. A source's
 * Reload, a skipped emission or an invalid structural change replaces only that source's
 * range (see [changeWithin]), so the other source keeps its rows.
 */
fun <T> DeltaList<T>.concat(other: DeltaList<T>): DeltaList<T> {
    val upstream = this
    return flow {
        val lifetime = CompositionLifetime()
        var previousFirst: SequencedDelta<T>? = null
        var previousSecond: SequencedDelta<T>? = null
        emitAll(
            combine(upstream.withEmissionSequence(), other.withEmissionSequence()) { first, second ->
                val sources = listOf(first.delta.items, second.delta.items)
                val combinedItems = lifecycleList(ConcatenatedList(sources[0], sources[1]), sources, lifetime.next()) {
                    concatenatedRoute(sources, it)
                }
                val oldFirst = previousFirst
                val oldSecond = previousSecond
                val change = if (oldFirst == null || oldSecond == null) {
                    Change.Reload
                } else {
                    val operations = first.changeWithin(oldFirst, offset = 0) +
                        second.changeWithin(oldSecond, offset = first.delta.items.size)
                    if (operations.isEmpty()) Change.Reload else Change.Mutations(operations)
                }
                previousFirst = first
                previousSecond = second
                Delta(combinedItems, change)
            }.onCompletion { cause -> if (cause != null) lifetime.close() }
        )
    }
}

/**
 * This source's change since [previous], as mutations of a concatenation in which it starts at
 * [offset], in running coordinates: none when it hasn't emitted; its own mutations when they
 * follow on from [previous]; otherwise (a Reload, a skipped emission or an invalid change) its
 * whole previous range removed and its new one inserted, leaving the other sources' rows alone.
 */
internal fun <T> SequencedDelta<T>.changeWithin(previous: SequencedDelta<T>, offset: Int): List<Mutation> {
    if (sequence == previous.sequence) return emptyList()
    if (isConsecutiveMutationOf(previous)) {
        return (delta.change as Change.Mutations).operations.map { it.offsetBy(offset) }
    }
    val oldSize = previous.delta.items.size
    val newSize = delta.items.size
    return listOfNotNull(
        Mutation.Remove(offset, oldSize).takeIf { oldSize > 0 },
        Mutation.Insert(offset, newSize).takeIf { newSize > 0 },
    )
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

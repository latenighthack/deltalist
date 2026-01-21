package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Lazy list that concatenates two lists without accessing items until needed.
 * Implements [SoftList] to propagate soft access from either source list.
 */
internal class ConcatenatedList<T>(
    private val first: List<T>,
    private val second: List<T>
) : AbstractList<T>(), SoftList<T> {
    override val size: Int get() = first.size + second.size

    override fun get(index: Int): T =
        if (index < first.size) first[index] else second[index - first.size]

    override fun softGet(index: Int): SoftValue<T>? {
        if (index < 0 || index >= size) return null

        return if (index < first.size) {
            if (first is SoftList<T>) {
                first.softGet(index)
            } else {
                SoftValue.Present(first[index])
            }
        } else {
            val secondIndex = index - first.size
            if (second is SoftList<T>) {
                second.softGet(secondIndex)
            } else {
                SoftValue.Present(second[secondIndex])
            }
        }
    }
}

fun <T> DeltaList<T>.concat(other: DeltaList<T>): DeltaList<T> = combine(this, other) { first, second ->
    val combinedItems = ConcatenatedList(first.items, second.items)

    val change = when {
        first.change is Change.Reload || second.change is Change.Reload -> Change.Reload
        first.change is Change.Mutations && second.change is Change.Mutations -> {
            val firstMutations = (first.change as Change.Mutations).operations
            val secondMutations = (second.change as Change.Mutations).operations.map { mutation ->
                when (mutation) {
                    is Mutation.Insert -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Remove -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Update -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Move -> mutation.copy(
                        fromIndex = mutation.fromIndex + first.items.size,
                        toIndex = mutation.toIndex + first.items.size
                    )
                }
            }
            Change.Mutations(firstMutations + secondMutations)
        }
        else -> Change.Reload
    }

    Delta(combinedItems, change)
}

fun <T> DeltaList<T>.header(item: T): DeltaList<T> {
    val headerFlow: DeltaList<T> = flowOf(Delta(listOf(item), Change.Reload))
    return headerFlow.concat(this)
}

fun <T> DeltaList<T>.footer(item: T): DeltaList<T> {
    val footerFlow: DeltaList<T> = flowOf(Delta(listOf(item), Change.Reload))
    return this.concat(footerFlow)
}

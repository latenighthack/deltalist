package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import kotlinx.coroutines.flow.flow

/** Sequence numbers belong to one collection and do not inspect or retain item values. */
internal data class SequencedDelta<T>(val delta: Delta<T>, val sequence: Long)

internal fun <T> DeltaList<T>.withEmissionSequence() = flow {
    var sequence = 0L
    collect { emit(SequencedDelta(it, sequence++)) }
}

/** Check continuity and running bounds using only sizes, never materializing lazy values. */
internal fun <T> SequencedDelta<T>.isConsecutiveMutationOf(previous: SequencedDelta<T>): Boolean {
    if (sequence != previous.sequence + 1) return false
    val change = delta.change as? Change.Mutations ?: return false
    var size = previous.delta.items.size.toLong()
    for (operation in change.operations) {
        when (operation) {
            is Mutation.Insert -> {
                if (operation.count < 0 || operation.index.toLong() !in 0..size) return false
                size += operation.count
            }
            is Mutation.Remove -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > size) return false
                size -= operation.count
            }
            is Mutation.Update -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > size) return false
            }
            is Mutation.Move -> {
                if (operation.count < 0 || operation.fromIndex < 0 || operation.toIndex < 0 ||
                    operation.fromIndex.toLong() + operation.count > size ||
                    operation.toIndex.toLong() + operation.count > size) return false
            }
        }
    }
    return size == delta.items.size.toLong()
}

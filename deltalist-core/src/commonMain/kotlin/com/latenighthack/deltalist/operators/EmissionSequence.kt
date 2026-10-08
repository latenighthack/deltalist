package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import kotlinx.coroutines.flow.flow

/** Sequence numbers belong to one collection and do not inspect or retain item values. */
internal data class SequencedDelta<T>(val delta: Delta<T>, val sequence: Long)

internal fun <T> DeltaList<T>.withEmissionSequence() = flow {
    var sequence = 0L
    collect { emit(SequencedDelta(it, sequence++)) }
}

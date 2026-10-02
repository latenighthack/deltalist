package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow

/** Bounded state delivery with collector-local mutation history. Writers must be serialized. */
internal class DeltaState<T>(initial: T, private val reload: (T) -> T) : Flow<T> {
    private data class Publication<T>(val revision: Long, val value: T)
    private val state = MutableStateFlow(Publication(0L, initial))

    var value: T
        get() = state.value.value
        set(value) {
            val previous = state.value
            state.value = Publication(previous.revision + 1, value)
        }

    override suspend fun collect(collector: FlowCollector<T>) {
        var previous: Long? = null
        state.collect { publication ->
            val value = if (previous != null && publication.revision == previous!! + 1) {
                publication.value
            } else {
                reload(publication.value)
            }
            collector.emit(value)
            previous = publication.revision
        }
    }
}

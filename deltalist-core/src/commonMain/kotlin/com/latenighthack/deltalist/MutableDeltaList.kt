package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow

interface MutableDeltaList<T> : Flow<Delta<T>> {
    val value: List<T>

    fun update(block: (MutableList<T>) -> Unit)

    fun reload(items: List<T>)
    fun append(item: T)
    fun append(items: List<T>)
    fun insert(index: Int, item: T)
    fun insert(index: Int, items: List<T>)
    fun removeAt(index: Int)
    fun removeRange(index: Int, count: Int)
    fun set(index: Int, item: T)
    fun move(fromIndex: Int, toIndex: Int)
    fun clear()
}

@Deprecated("Use MutableDeltaList instead", ReplaceWith("MutableDeltaList<T>"))
typealias MutableDeltaFlow<T> = MutableDeltaList<T>

internal class MutableDeltaListImpl<T>(
    initial: List<T>
) : MutableDeltaList<T> {
    private val state = MutableStateFlow(Delta(initial, Change.Reload))

    override val value: List<T> get() = state.value.items

    override fun update(block: (MutableList<T>) -> Unit) {
        val tracked = TrackedMutableList(state.value.items)
        block(tracked)

        val mutations = tracked.toMutations()
        if (mutations.isEmpty()) {
            return
        }

        state.value = Delta(tracked.toList(), Change.Mutations(mutations))
    }

    override fun reload(items: List<T>) {
        state.value = Delta(items, Change.Reload)
    }

    override fun append(item: T) = update { it.add(item) }

    override fun append(items: List<T>) = update { it.addAll(items) }

    override fun insert(index: Int, item: T) = update { it.add(index, item) }

    override fun insert(index: Int, items: List<T>) = update { it.addAll(index, items) }

    override fun removeAt(index: Int) = update { it.removeAt(index) }

    override fun removeRange(index: Int, count: Int) = update {
        for (i in 0 until count) {
            it.removeAt(index)
        }
    }

    override fun set(index: Int, item: T) = update { it[index] = item }

    override fun move(fromIndex: Int, toIndex: Int) {
        val tracked = TrackedMutableList(state.value.items)
        tracked.move(fromIndex, toIndex)

        val mutations = tracked.toMutations()
        if (mutations.isEmpty()) {
            return
        }

        state.value = Delta(tracked.toList(), Change.Mutations(mutations))
    }

    override fun clear() = update { it.clear() }

    override suspend fun collect(collector: FlowCollector<Delta<T>>) {
        state.collect(collector)
    }
}

fun <T> mutableDeltaListOf(initial: List<T> = emptyList()): MutableDeltaList<T> =
    MutableDeltaListImpl(initial)

@Deprecated("Use mutableDeltaListOf instead", ReplaceWith("mutableDeltaListOf(initial)"))
fun <T> mutableDeltaFlowOf(initial: List<T> = emptyList()): MutableDeltaList<T> =
    mutableDeltaListOf(initial)

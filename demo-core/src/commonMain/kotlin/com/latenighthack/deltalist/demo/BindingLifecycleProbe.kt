package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.flow.flow

/** Native test/demo fixture exported by a consumer framework, exercising the erased Swift ABI. */
class BindingProbeRow(val key: String)

class BindingLifecycleProbe {
    private val source = mutableDeltaListOf<String>()
    private var latest: Delta<BindingProbeRow>? = null
    var activeCollectors: Int = 0
        private set
    val acquiredCount: Int get() {
        val list = latest?.items as? LazyList<*> ?: return 0
        return (0 until list.size).count { list.isAcquired(it) }
    }
    val items: DeltaList<BindingProbeRow> = flow {
        activeCollectors++
        try {
            source.lazyMap { BindingProbeRow(it) }.collect {
                latest = it
                emit(it)
            }
        } finally { activeCollectors-- }
    }
    fun append(key: String) = source.append(key)
    fun move(from: Int, to: Int) = source.move(from, to)
    fun clear() = source.clear()
    fun reload(keys: List<String>) = source.reload(keys)
}

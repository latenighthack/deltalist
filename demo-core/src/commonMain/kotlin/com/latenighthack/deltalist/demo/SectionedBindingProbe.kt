package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.groupBy
import com.latenighthack.deltalist.operators.lazyMapItems
import kotlinx.coroutines.flow.onEach

/** A lazily mapped row of [SectionedBindingProbe]; identity tells whether the cache served it. */
class SectionedProbeRow(val key: String)

/**
 * Native test fixture exported by a consumer framework: entries grouped by category, with each
 * section's rows lazily mapped, so sections come and go as categories gain and lose entries.
 */
class SectionedBindingProbe {
    private val entries = mutableDeltaListOf<Pair<String, String>>()
    private var latest: SectionedDelta<String, SectionedProbeRow>? = null

    /** How many rows the mapper has built. */
    var built: Int = 0
        private set

    val acquiredCount: Int get() = latest?.sections.orEmpty().sumOf { section ->
        val rows = section.items as? LazyList<*> ?: return@sumOf 0
        (0 until rows.size).count { rows.isAcquired(it) }
    }

    val sections: SectionedDeltaList<String, SectionedProbeRow> = entries
        .groupBy { it.first }
        .lazyMapItems { (_, key) -> SectionedProbeRow(key).also { built++ } }
        .onEach { latest = it }

    fun insert(index: Int, category: String, key: String) = entries.insert(index, category to key)
    fun append(category: String, key: String) = entries.append(category to key)
    fun removeAt(index: Int) = entries.removeAt(index)
    fun replace(index: Int, category: String, key: String) = entries.set(index, category to key)
}

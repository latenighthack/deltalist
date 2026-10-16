package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.filterItems
import com.latenighthack.deltalist.operators.sectionedDeltaList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Native test fixture: three pages of two numbers, split into an even and an odd section over the
 * one paged list, so a sectioned binding has to ask its sections for their next slots to load
 * every page.
 */
class SectionedPagingProbe {
    /** The pages fetched so far, by token. */
    var fetched: List<Int> = emptyList()
        private set

    private val pages: DeltaList<String> = paginatedDeltaList(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        startToken = 0,
    ) { _, token ->
        fetched = fetched + token
        Page(listOf("${token * 2}", "${token * 2 + 1}"), null, if (token < 2) token + 1 else null)
    }

    val sections: SectionedDeltaList<String, String> = sectionedDeltaList(
        "even" to pages.filterItems { it.toInt() % 2 == 0 },
        "odd" to pages.filterItems { it.toInt() % 2 == 1 },
    )
}

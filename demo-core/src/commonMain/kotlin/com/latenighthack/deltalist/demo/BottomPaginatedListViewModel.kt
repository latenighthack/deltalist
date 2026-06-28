package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.mutableDeltaListOf
import com.latenighthack.deltalist.operators.concat
import com.latenighthack.deltalist.operators.filterItemsDynamic
import com.latenighthack.deltalist.paginatedDeltaList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Bottom-anchored ("chat-style") pagination. Same simulated backend as
 * [PaginatedListViewModel] (2,000 items, 20/page, ~500ms latency, divisor filter) but the
 * initial fetch is the *last* page, so only the bottom items load first; scrolling up triggers
 * BEFORE fetches that prepend older pages.
 */
class BottomPaginatedListViewModel {
    private val pageSize = 20
    private val totalItems = 2_000
    private val lastPage = (totalItems - 1) / pageSize

    private val _loadingDirection = MutableStateFlow<LoadDirection?>(null)
    val loadingDirection: StateFlow<LoadDirection?> = _loadingDirection.asStateFlow()

    private val _loadedCount = MutableStateFlow(0)
    val loadedCount: StateFlow<Int> = _loadedCount.asStateFlow()

    private val baseHistory: DeltaList<Int> = paginatedDeltaList(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        fetchWindowSize = 2,
        startToken = lastPage,
        initialEstimatedSize = totalItems
    ) { direction, pageToken ->
        _loadingDirection.value = direction

        try {
            delay(500)

            val startIndex = pageToken * pageSize
            val endIndex = minOf(startIndex + pageSize, totalItems)

            val items = (startIndex until endIndex).toList()

            _loadedCount.value = _loadedCount.value + items.size

            Page(
                items = items,
                // Scroll up loads older pages; there is nothing below the bottom page.
                beforeToken = if (pageToken > 0) pageToken - 1 else null,
                afterToken = null,
                estimatedTotalSize = totalItems
            )
        } finally {
            _loadingDirection.value = null
        }
    }

    private val _excludeDivisors = MutableStateFlow<Set<Int>>(emptySet())
    val excludeDivisors: StateFlow<Set<Int>> = _excludeDivisors.asStateFlow()

    // Filter the paginated history only, so manually-added items below stay visible.
    private val filteredHistory: DeltaList<Int> = baseHistory
        .filterItemsDynamic(
            _excludeDivisors.map { divisors ->
                { number: Int -> divisors.none { d -> number % d == 0 } }
            }
        )

    // Mutable regions around the read-only paginated history. Added items use unique negative
    // values so their list keys never collide with the 0..1999 paginated values.
    private val _topItems = mutableDeltaListOf<Int>()
    private val _bottomItems = mutableDeltaListOf<Int>()
    private var addedCounter = 0

    val messages: DeltaList<Int> = _topItems.concat(filteredHistory).concat(_bottomItems)

    fun addAtTop() {
        _topItems.insert(0, --addedCounter)
    }

    fun addAtBottom() {
        _bottomItems.append(--addedCounter)
    }

    fun toggleDivisorFilter(divisor: Int) {
        _excludeDivisors.value = if (divisor in _excludeDivisors.value) {
            _excludeDivisors.value - divisor
        } else {
            _excludeDivisors.value + divisor
        }
    }
}

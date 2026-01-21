package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.operators.filterItemsDynamic
import com.latenighthack.deltalist.paginatedDeltaFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class PaginatedListViewModel {
    private val _paginatedLoadingDirection = MutableStateFlow<LoadDirection?>(null)
    val paginatedLoadingDirection: StateFlow<LoadDirection?> = _paginatedLoadingDirection.asStateFlow()

    private val _paginatedLoadedCount = MutableStateFlow(0)
    val paginatedLoadedCount: StateFlow<Int> = _paginatedLoadedCount.asStateFlow()

    private val basePaginatedNumbers: DeltaFlow<Int> = paginatedDeltaFlow(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        fetchWindowSize = 2,
        startToken = 0
    ) { direction, pageToken ->
        _paginatedLoadingDirection.value = direction

        try {
            delay(500)

            val pageSize = 20
            val totalItems = 10_000
            val startIndex = pageToken * pageSize
            val endIndex = minOf(startIndex + pageSize, totalItems)

            val items = (startIndex until endIndex).toList()

            _paginatedLoadedCount.value = endIndex

            Page(
                items = items,
                beforeToken = if (pageToken > 0) pageToken - 1 else null,
                afterToken = if (endIndex < totalItems) pageToken + 1 else null,
                estimatedTotalSize = totalItems
            )
        } finally {
            _paginatedLoadingDirection.value = null
        }
    }

    private val _excludeDivisors = MutableStateFlow<Set<Int>>(emptySet())
    val excludeDivisors: StateFlow<Set<Int>> = _excludeDivisors.asStateFlow()

    val paginatedNumbers: DeltaFlow<Int> = basePaginatedNumbers
        .filterItemsDynamic(
            _excludeDivisors.map { divisors ->
                { number: Int -> divisors.none { d -> number % d == 0 } }
            }
        )

    fun toggleDivisorFilter(divisor: Int) {
        _excludeDivisors.value = if (divisor in _excludeDivisors.value) {
            _excludeDivisors.value - divisor
        } else {
            _excludeDivisors.value + divisor
        }
    }
}

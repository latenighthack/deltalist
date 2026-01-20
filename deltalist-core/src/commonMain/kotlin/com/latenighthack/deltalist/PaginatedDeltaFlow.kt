package com.latenighthack.deltalist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Direction of a pagination fetch operation.
 */
enum class LoadDirection {
    /** Initial load when the list is first accessed */
    INITIAL,
    /** Loading items before the current start of the list */
    BEFORE,
    /** Loading items after the current end of the list */
    AFTER
}

/**
 * Creates a paginated [DeltaFlow] that lazily fetches pages of data as items near
 * the boundaries of the loaded data are accessed.
 *
 * @param T The type of items in the list
 * @param U The type of pagination tokens (internal, not exposed)
 * @param scope The coroutine scope used for background fetch operations
 * @param fetchWindowSize The number of items from each boundary that triggers a fetch.
 *        When accessing an item within this distance from the start or end of the loaded
 *        items, a fetch will be triggered if there's more data available.
 * @param startToken The initial token to use for the first fetch
 * @param fetch The suspend function that fetches a page. Receives the load direction
 *        and the token for that direction. The closure can use the direction to manage
 *        its own loading state (e.g., emit to a separate loading flow).
 */
fun <T, U> paginatedDeltaFlow(
    scope: CoroutineScope,
    fetchWindowSize: Int = 1,
    startToken: U,
    fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
): DeltaFlow<T> = PaginatedDeltaFlowImpl(scope, fetchWindowSize, startToken, fetch)

internal class PaginatedDeltaFlowImpl<T, U>(
    private val scope: CoroutineScope,
    private val fetchWindowSize: Int,
    private val startToken: U,
    private val fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
) : DeltaFlow<T> {

    private val mutex = Mutex()

    // Internal state
    private val _items = mutableListOf<T>()
    private var _beforeToken: U? = null
    private var _afterToken: U? = null
    private var _estimatedTotalSize: Int? = null
    private var _isLoadingBefore = false
    private var _isLoadingAfter = false
    private var _initialLoadDone = false

    private val state = MutableStateFlow<Delta<T>>(
        Delta(createWrapper(), Change.Reload)
    )

    private fun createWrapper(): PaginatedListWrapper<T> {
        return PaginatedListWrapper(
            items = _items.toList(),
            estimatedTotalSize = _estimatedTotalSize,
            fetchWindowSize = fetchWindowSize,
            hasMoreBefore = _beforeToken != null,
            hasMoreAfter = _afterToken != null || !_initialLoadDone,
            onAccessNearStart = { triggerBeforeFetch() },
            onAccessNearEnd = { triggerAfterFetch() },
            onAccessWhenEmpty = { triggerInitialFetch() }
        )
    }

    private fun triggerInitialFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_initialLoadDone || _isLoadingAfter) return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_initialLoadDone || _isLoadingAfter) return@launch
                _isLoadingAfter = true
            }

            try {
                val page = fetch(LoadDirection.INITIAL, startToken)

                mutex.withLock {
                    _items.addAll(page.items)
                    _beforeToken = page.beforeToken
                    _afterToken = page.afterToken
                    _estimatedTotalSize = page.estimatedTotalSize
                    _initialLoadDone = true
                    _isLoadingAfter = false

                    emitChange(page.items.size, isAppend = true, isInitial = true)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingAfter = false
                }
                throw e
            }
        }
    }

    private fun triggerBeforeFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_isLoadingBefore) return
        val token = _beforeToken ?: return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_isLoadingBefore || _beforeToken == null) return@launch
                _isLoadingBefore = true
            }

            try {
                val page = fetch(LoadDirection.BEFORE, token)

                mutex.withLock {
                    val insertCount = page.items.size
                    _items.addAll(0, page.items)
                    _beforeToken = page.beforeToken

                    if (page.estimatedTotalSize != null) {
                        _estimatedTotalSize = page.estimatedTotalSize
                    }

                    _isLoadingBefore = false

                    emitChange(insertCount, isAppend = false, isInitial = false)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingBefore = false
                }
                throw e
            }
        }
    }

    private fun triggerAfterFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_isLoadingAfter) return
        val token = _afterToken ?: return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_isLoadingAfter || _afterToken == null) return@launch
                _isLoadingAfter = true
            }

            try {
                val page = fetch(LoadDirection.AFTER, token)

                mutex.withLock {
                    val previousSize = _items.size
                    val insertCount = page.items.size
                    _items.addAll(page.items)
                    _afterToken = page.afterToken

                    if (page.estimatedTotalSize != null) {
                        _estimatedTotalSize = page.estimatedTotalSize
                    }

                    _isLoadingAfter = false

                    emitChange(insertCount, isAppend = true, isInitial = false, previousRealSize = previousSize)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingAfter = false
                }
                throw e
            }
        }
    }

    private fun emitChange(count: Int, isAppend: Boolean, isInitial: Boolean, previousRealSize: Int = 0) {
        if (count == 0 && !isInitial) return

        val currentList = createWrapper()

        if (isInitial) {
            state.value = Delta(currentList, Change.Reload)
        } else {
            val estimated = _estimatedTotalSize

            if (isAppend) {
                val insertIndex = previousRealSize
                val coveredByEstimate = if (estimated != null) {
                    previousRealSize < estimated
                } else {
                    false
                }

                if (coveredByEstimate && estimated != null) {
                    val coveredCount = minOf(count, estimated - previousRealSize)
                    val uncoveredCount = count - coveredCount

                    val mutations = mutableListOf<Mutation>()

                    if (coveredCount > 0) {
                        mutations.add(Mutation.Update(insertIndex, coveredCount))
                    }

                    if (uncoveredCount > 0) {
                        mutations.add(Mutation.Insert(insertIndex + coveredCount, uncoveredCount))
                    }

                    state.value = Delta(currentList, Change.Mutations(mutations))
                } else {
                    state.value = Delta(
                        currentList,
                        Change.Mutations(Mutation.Insert(insertIndex, count))
                    )
                }
            } else {
                state.value = Delta(
                    currentList,
                    Change.Mutations(Mutation.Insert(0, count))
                )
            }
        }
    }

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Delta<T>>) {
        // Trigger initial fetch when collection starts
        if (!_initialLoadDone && _items.isEmpty()) {
            triggerInitialFetch()
        }
        state.collect(collector)
    }
}

/**
 * A list wrapper that intercepts access to trigger pagination fetches and reports estimated size.
 * Implements [SoftList] to allow operators to inspect values without triggering fetches.
 */
internal class PaginatedListWrapper<T>(
    private val items: List<T>,
    private val estimatedTotalSize: Int?,
    private val fetchWindowSize: Int,
    private val hasMoreBefore: Boolean,
    private val hasMoreAfter: Boolean,
    private val onAccessNearStart: () -> Unit,
    private val onAccessNearEnd: () -> Unit,
    private val onAccessWhenEmpty: () -> Unit
) : AbstractList<T>(), SoftList<T> {

    override val size: Int
        get() {
            val realSize = items.size
            val estimated = estimatedTotalSize
            return if (estimated != null && estimated > realSize) {
                estimated
            } else {
                realSize
            }
        }

    override fun get(index: Int): T {
        val realSize = items.size

        // Trigger fetch if empty and we might have data
        if (realSize == 0 && hasMoreAfter) {
            onAccessWhenEmpty()
            throw IndexOutOfBoundsException("List is empty, fetch triggered. Index: $index")
        }

        // Check if accessing near the start (within fetch window)
        if (hasMoreBefore && index < fetchWindowSize) {
            onAccessNearStart()
        }

        // Check if accessing near the end of loaded items (within fetch window)
        if (hasMoreAfter && index >= realSize - fetchWindowSize) {
            onAccessNearEnd()
        }

        // For indices beyond loaded items, throw
        if (index >= realSize) {
            throw IndexOutOfBoundsException("Index $index is beyond loaded items (loaded: $realSize)")
        }

        if (index < 0) {
            throw IndexOutOfBoundsException("Index $index is negative")
        }

        return items[index]
    }

    override fun softGet(index: Int): SoftValue<T>? {
        val realSize = items.size
        val effectiveSize = estimatedTotalSize?.let { maxOf(it, realSize) } ?: realSize

        // Out of bounds
        if (index < 0 || index >= effectiveSize) return null

        // Within bounds but not yet loaded
        if (index >= realSize) return SoftValue.NotLoaded

        // Loaded
        return SoftValue.Present(items[index])
    }

    // Override equals/hashCode to avoid triggering fetches during StateFlow comparison
    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PaginatedListWrapper<*>) return false
        return items == other.items && estimatedTotalSize == other.estimatedTotalSize
    }

    override fun hashCode(): Int {
        var result = items.hashCode()
        result = 31 * result + (estimatedTotalSize ?: 0)
        return result
    }
}

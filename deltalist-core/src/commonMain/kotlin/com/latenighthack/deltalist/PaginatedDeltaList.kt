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
 * Creates a paginated [DeltaList] that lazily fetches pages of data as items near
 * the boundaries of the loaded data are accessed.
 *
 * @param T The type of items in the list
 * @param U The type of pagination tokens (internal, not exposed)
 * @param scope The coroutine scope used for background fetch operations
 * @param fetchWindowSize The number of items from each boundary that triggers a fetch.
 *        When accessing an item within this distance from the start or end of the loaded
 *        items, a fetch will be triggered if there's more data available.
 * @param startToken The initial token to use for the first fetch
 * @param initialEstimatedSize Optional estimated total size to report *before* the first page
 *        loads. When set, the initial (still-loading) snapshot renders this many NotLoaded
 *        placeholder rows instead of a single one, so the initial fetch shows a full column of
 *        skeleton items. The first page's [Page.estimatedTotalSize] then takes over.
 * @param fetch The suspend function that fetches a page. Receives the load direction
 *        and the token for that direction. The closure can use the direction to manage
 *        its own loading state (e.g., emit to a separate loading flow).
 */
fun <T, U> paginatedDeltaList(
    scope: CoroutineScope,
    fetchWindowSize: Int = 1,
    startToken: U,
    initialEstimatedSize: Int? = null,
    fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
): DeltaList<T> = PaginatedDeltaListImpl(scope, fetchWindowSize, startToken, initialEstimatedSize, fetch)

internal class PaginatedDeltaListImpl<T, U>(
    private val scope: CoroutineScope,
    private val fetchWindowSize: Int,
    private val startToken: U,
    initialEstimatedSize: Int?,
    private val fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
) : DeltaList<T> {

    private val mutex = Mutex()

    // Internal state
    private val _items = mutableListOf<T>()
    private var _beforeToken: U? = null
    private var _afterToken: U? = null
    // Seeded with [initialEstimatedSize] so the pre-load snapshot renders a full column of
    // skeleton rows; the first fetched page's estimate then replaces it.
    private var _estimatedTotalSize: Int? = initialEstimatedSize
    // Estimated number of unloaded items *before* the loaded window (the leading "load earlier"
    // region). Drives per-item leading placeholders, symmetric to the trailing estimate. Only
    // meaningful when an estimate is known and the loaded window is anchored at/near the bottom;
    // with a null estimate the wrapper falls back to a single leading placeholder.
    private var _leadingEstimate = 0
    private var _isLoadingBefore = false
    private var _isLoadingAfter = false
    private var _initialLoadDone = false

    private val state = MutableStateFlow<Delta<T>>(
        Delta(createWrapper(), Change.Reload)
    )

    // Bumped per emitted snapshot. The fetch-trigger closures below capture the generation
    // they were created in and no-op once superseded, so a stale snapshot's request() can't
    // drive a fetch (decision A: side effects honored only on the current snapshot).
    private var generation = 0

    private fun createWrapper(): PaginatedListWrapper<T> {
        generation += 1
        val myGen = generation
        val leading = currentLeading()
        return PaginatedListWrapper(
            items = _items.toList(),
            leading = leading,
            trailing = currentTrailing(leading),
            hasMoreBefore = _beforeToken != null,
            hasMoreAfter = _afterToken != null || !_initialLoadDone,
            onAccessNearStart = { if (myGen == generation) triggerBeforeFetch() },
            onAccessNearEnd = { if (myGen == generation) triggerAfterFetch() }
        )
    }

    /** Number of leading "load earlier" placeholders to display. */
    private fun currentLeading(): Int {
        if (_beforeToken == null) return 0
        val est = _estimatedTotalSize
        // Estimate-driven inflation when known; otherwise a single anti-stall placeholder so the
        // UI always has a NotLoaded slot at the top whose request() drives the before-fetch.
        return if (est != null && _leadingEstimate > 0) _leadingEstimate else 1
    }

    /** Number of trailing "load more" placeholders to display, given the [leading] count. */
    private fun currentTrailing(leading: Int): Int {
        val hasMoreAfter = _afterToken != null || !_initialLoadDone
        if (!hasMoreAfter) return 0
        val est = _estimatedTotalSize
        val real = _items.size
        return if (est != null && est > real + leading) est - real - leading else 1
    }

    private fun computeInitialLeadingEstimate(before: U?, after: U?, est: Int?, loaded: Int): Int = when {
        before == null -> 0                       // loaded window is at the very top
        est == null -> 0                          // no estimate → single-placeholder fallback
        after == null -> maxOf(0, est - loaded)   // anchored at the bottom: all remaining are above
        else -> 0                                 // bidirectional from the middle: inflate the trailing side
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
                    _leadingEstimate = computeInitialLeadingEstimate(
                        page.beforeToken, page.afterToken, page.estimatedTotalSize, page.items.size
                    )
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

                    // The prepended items fill leading placeholders. Once earlier history is
                    // exhausted (no more before), drop any remaining phantom leading placeholders.
                    _leadingEstimate = if (page.beforeToken == null) 0 else maxOf(0, _leadingEstimate - insertCount)

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

    // The display size and leading-placeholder count the last emitted Delta reported.
    private var _lastDisplaySize = 0
    private var _lastLeading = 0

    private fun emitChange(count: Int, isAppend: Boolean, isInitial: Boolean, previousRealSize: Int = 0) {
        val currentList = createWrapper()
        val newDisplaySize = currentList.size
        val newLeading = currentLeading()

        if (isInitial) {
            _lastDisplaySize = newDisplaySize
            _lastLeading = newLeading
            state.value = Delta(currentList, Change.Reload)
            return
        }

        // Nothing changed structurally (e.g. an empty page that didn't toggle a placeholder).
        if (count == 0 && newDisplaySize == _lastDisplaySize && newLeading == _lastLeading) return

        val oldDisplaySize = _lastDisplaySize
        val oldLeading = _lastLeading
        val mutations = mutableListOf<Mutation>()
        var working = oldDisplaySize

        if (isAppend) {
            // New items land just after the leading placeholders + previously-loaded items.
            // Leading is unchanged by an after-fetch, so newLeading == oldLeading here.
            val insertIndex = newLeading + previousRealSize
            val coveredEnd = minOf(insertIndex + count, oldDisplaySize)
            val coveredCount = (coveredEnd - insertIndex).coerceAtLeast(0)
            if (coveredCount > 0) {
                mutations.add(Mutation.Update(insertIndex, coveredCount))
            }
            val insertedBeyond = count - coveredCount
            if (insertedBeyond > 0) {
                mutations.add(Mutation.Insert(oldDisplaySize, insertedBeyond))
                working += insertedBeyond
            }
        } else {
            // Before-fetch: `count` real items were prepended at the front of the real window.
            // How the leading placeholder region (oldLeading -> newLeading) absorbs them:
            when {
                newLeading == 0 -> {
                    // Reached the top. The first `min(count, oldLeading)` leading placeholders
                    // become the prepended items; extra items insert; leftover phantom
                    // placeholders (a too-large estimate) are removed.
                    val filled = minOf(count, oldLeading)
                    if (filled > 0) mutations.add(Mutation.Update(0, filled))
                    if (count > oldLeading) {
                        mutations.add(Mutation.Insert(oldLeading, count - oldLeading))
                        working += count - oldLeading
                    } else if (oldLeading > count) {
                        mutations.add(Mutation.Remove(count, oldLeading - count))
                        working -= oldLeading - count
                    }
                }
                newLeading == oldLeading -> {
                    // Leading unchanged (a single anti-stall placeholder persists): the items are
                    // inserted just after it.
                    if (count > 0) {
                        mutations.add(Mutation.Insert(oldLeading, count))
                        working += count
                    }
                }
                newLeading == oldLeading - count -> {
                    // Estimate-driven: the bottom `count` leading placeholders become present in
                    // place — no size change, so no scroll jump.
                    if (count > 0) mutations.add(Mutation.Update(newLeading, count))
                }
                else -> {
                    // Unexpected leading transition: fall back to a full reload (always correct).
                    _lastDisplaySize = newDisplaySize
                    _lastLeading = newLeading
                    state.value = Delta(currentList, Change.Reload)
                    return
                }
            }
        }

        // Reconcile remaining placeholder delta at the trailing end: shrink removes phantoms
        // a short last page revealed; growth adds placeholders from a larger estimate.
        if (working > newDisplaySize) {
            mutations.add(Mutation.Remove(newDisplaySize, working - newDisplaySize))
        } else if (working < newDisplaySize) {
            mutations.add(Mutation.Insert(working, newDisplaySize - working))
        }

        _lastDisplaySize = newDisplaySize
        _lastLeading = newLeading
        if (mutations.isEmpty()) return
        state.value = Delta(currentList, Change.Mutations(mutations))
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
    private val leading: Int,
    private val trailing: Int,
    private val hasMoreBefore: Boolean,
    private val hasMoreAfter: Boolean,
    private val onAccessNearStart: () -> Unit,
    private val onAccessNearEnd: () -> Unit
) : AbstractSoftList<T>() {

    // [leading] and [trailing] are computed by the producer (estimate-driven inflation,
    // symmetric per-item placeholders on each side, or a single anti-stall placeholder).

    override val size: Int get() = leading + items.size + trailing

    override fun softGet(index: Int): SoftValue<T>? {
        // Pure peek (no fetch side effects). Bounds use the gated [size]. The trigger
        // closures are epoch-guarded by the producer, so a superseded snapshot's request()
        // is a safe no-op.
        if (index < 0 || index >= size) return null

        if (index < leading) {
            // Leading "load earlier" placeholder.
            return SoftValue.NotLoaded { if (hasMoreBefore) onAccessNearStart() }
        }

        val realIndex = index - leading
        if (realIndex < items.size) {
            return SoftValue.Present(items[realIndex])
        }

        // Trailing "load more" placeholder.
        return SoftValue.NotLoaded { if (hasMoreAfter) onAccessNearEnd() }
    }
}

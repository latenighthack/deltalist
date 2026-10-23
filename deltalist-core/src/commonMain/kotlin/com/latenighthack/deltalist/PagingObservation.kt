package com.latenighthack.deltalist

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Paging demand and fetch work reported by [paginatedDeltaList].
 *
 * Events are delivered synchronously to each [PagingObserver]. An observer must not block; a
 * throw from an observer is caught and discarded.
 */
sealed class PagingEvent {
    /**
     * A NotLoaded placeholder had `request()` called on it, or (for [LoadDirection.INITIAL]) a
     * collection of the list started.
     *
     * Reported even when no fetch follows: a fetch in that direction is already running, the
     * requesting snapshot is no longer the current one, or there is nothing more to load.
     *
     * @property direction [LoadDirection.BEFORE] for a leading placeholder,
     *   [LoadDirection.AFTER] for a trailing placeholder, [LoadDirection.INITIAL] for the start
     *   of a collection.
     * @property placeholderRange Display indices, within the snapshot that made the request, of
     *   the placeholder region on the requesting side. BEFORE: `0 until leading`. AFTER:
     *   `leading + loadedCount until size`. INITIAL: `0 until size` of the placeholder snapshot
     *   the list publishes before its first page loads (empty when that snapshot has no rows).
     */
    data class Requested(val direction: LoadDirection, val placeholderRange: IntRange) : PagingEvent()

    /** A call to the list's fetch function began. [fetchId] is unique within the process. */
    data class Started(val fetchId: Long, val direction: LoadDirection) : PagingEvent()

    /**
     * The fetch returned and its page has been applied to the list.
     *
     * @property itemCount Number of items in the returned page.
     * @property duration Time from [Started] until the page was applied.
     */
    data class Completed(
        val fetchId: Long,
        val direction: LoadDirection,
        val itemCount: Int,
        val duration: Duration
    ) : PagingEvent()

    /** The fetch threw [error] (not a cancellation). The error is still rethrown into the list's scope. */
    data class Failed(
        val fetchId: Long,
        val direction: LoadDirection,
        val error: Throwable,
        val duration: Duration
    ) : PagingEvent()

    /** The fetch coroutine was cancelled (for example, the list's scope was cancelled mid-fetch). */
    data class Cancelled(
        val fetchId: Long,
        val direction: LoadDirection,
        val duration: Duration
    ) : PagingEvent()
}

/** Receives [PagingEvent]s. Called synchronously; must not block. */
fun interface PagingObserver {
    fun onPaging(event: PagingEvent)
}

/**
 * Installs [observer] for paginated lists collected in this coroutine context, for example via
 * `list.flowOn(PagingObservation(observer))`.
 *
 * A list registers the observer when a collection with this element in its context starts, and
 * removes it when that collection ends. While registered, the observer receives every event of
 * that list, including events caused by other collectors.
 */
class PagingObservation(val observer: PagingObserver) : AbstractCoroutineContextElement(PagingObservation) {
    companion object Key : CoroutineContext.Key<PagingObservation>
}

@OptIn(ExperimentalAtomicApi::class)
private val nextFetchId = AtomicLong(0L)

@OptIn(ExperimentalAtomicApi::class)
internal fun newPagingFetchId(): Long = nextFetchId.incrementAndFetch()

/** Delivers [event] to each observer, discarding anything an observer throws. */
internal fun List<PagingObserver>.deliver(event: PagingEvent) {
    for (observer in this) {
        try {
            observer.onPaging(event)
        } catch (_: Throwable) {
            // Observers must never affect paging.
        }
    }
}

/**
 * Copy-on-write set of observers for one paginated list: the explicit observer (if any) plus one
 * entry per active collection that carries a [PagingObservation]. Reading is a single atomic load
 * with no allocation.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PagingObserverRegistry(explicit: PagingObserver?) {
    private val current = AtomicReference(if (explicit == null) emptyList() else listOf(explicit))

    fun snapshot(): List<PagingObserver> = current.load()

    fun add(observer: PagingObserver) {
        while (true) {
            val old = current.load()
            if (current.compareAndSet(old, old + observer)) return
        }
    }

    /** Removes one registration of [observer] (by identity). */
    fun remove(observer: PagingObserver) {
        while (true) {
            val old = current.load()
            val index = old.indexOfLast { it === observer }
            if (index < 0) return
            val updated = old.toMutableList().also { it.removeAt(index) }
            if (current.compareAndSet(old, updated)) return
        }
    }
}

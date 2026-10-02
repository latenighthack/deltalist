package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.LeasedLazyList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.asSoftList
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lazy transformation with acquire/release semantics for memory management.
 *
 * Thread-safe: Uses lock-free atomic operations to handle concurrent access
 * from delta producers (any thread) and UI consumers (typically main thread).
 *
 * Returns a DeltaList where items are backed by [LazyList]:
 * - Accessing items via `get()` computes and caches the transformation
 * - Platform adapters detect [LazyList] and call `release()` automatically
 * - Items track position changes across mutations
 *
 * Example:
 * ```kotlin
 * val items: DeltaList<MyItem> = ...
 * val transformed: DeltaList<TransformedItem> = items.lazyMap { transform(it) }
 *
 * // In Compose - adapters handle release automatically:
 * items(delta.items, key = { it.stableId }) { item ->
 *     // item is auto-acquired on access, auto-released on disposal
 *     MyCard(item)
 * }
 * ```
 *
 * @param transform The transformation function to apply lazily
 * @return A DeltaList backed by LazyList for automatic lifecycle management
 */
fun <T, R> DeltaList<T>.lazyMap(transform: (T) -> R): DeltaList<R> = flow {
    val state = LazyMapState(transform)

    try {
        collect { delta ->
            state.applyDelta(delta)
            emit(Delta(state.asList(), delta.change))
        }
    } catch (error: Throwable) {
        state.close()
        throw error
    }
}

/**
 * Simple transformation without caching - transforms on every access.
 * Use this when transformations are cheap or when you don't need retention semantics.
 *
 * This is similar to [map] but defers transformation to access time rather than
 * transforming all items upfront.
 */
fun <T, R> DeltaList<T>.deferredMap(transform: (T) -> R): DeltaList<R> = flow {
    collect { delta ->
        emit(Delta(
            items = SimpleLazyMapList(delta.items, transform),
            change = delta.change
        ))
    }
}

/**
 * Simple lazy list that transforms on every access (no caching).
 * Implements [SoftList] to propagate soft access from the source.
 */
internal class SimpleLazyMapList<T, R>(
    private val source: SoftList<T>,
    private val transform: (T) -> R
) : AbstractSoftList<R>() {
    override val size: Int get() = source.size

    override fun softGet(index: Int): SoftValue<R>? =
        when (val soft = source.softGet(index)) {
            is SoftValue.Present -> SoftValue.Present(transform(soft.value))
            is SoftValue.NotLoaded -> soft
            null -> null
        }
}

/**
 * Thread-safe state for lazy mapping with acquisition tracking.
 *
 * Uses atomic CAS operations to ensure consistency between:
 * - Delta producers calling applyDelta() (potentially background thread)
 * - UI consumers calling get()/release() (typically main thread)
 *
 * All operations are lock-free and use immutable snapshots.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LazyMapState<S, T>(
    private val transform: (S) -> T
) {
    /**
     * A cached transformed value together with how many live acquirers reference it.
     * Entries are evicted only when [refCount] reaches zero, so two bindings on the same
     * index (e.g. a sticky header and a row) no longer evict each other's value.
     */
    private data class CacheEntry<T>(val value: T, val refCount: Int, val token: Any = Any())

    /**
     * Immutable snapshot of the current state.
     * Updated atomically via CAS.
     */
    private data class Snapshot<S, T>(
        val source: SoftList<S>,
        val cache: Map<Int, CacheEntry<T>>,
        // Bumped once per applyDelta. A LazyList view captures the epoch it was created in;
        // its lifecycle side effects (acquire/release) are honored only while still current,
        // so a held, superseded view can never mutate the live cache.
        val epoch: Int = 0
    )

    private val snapshot = AtomicReference<Snapshot<S, T>>(Snapshot(emptyList<S>().asSoftList(), emptyMap()))

    /**
     * Apply a delta, atomically updating the source and transforming cache indices.
     * Uses CAS loop to handle concurrent modifications from get/release.
     */
    fun applyDelta(delta: Delta<S>) {
        while (true) {
            val current = snapshot.load()
            val newCache: Map<Int, CacheEntry<T>> = when (val change = delta.change) {
                is Change.Reload -> emptyMap()
                is Change.Mutations -> {
                    var cache = current.cache
                    for (mutation in change.operations) {
                        cache = applyMutationToCache(cache, mutation, delta.items)
                    }
                    cache
                }
            }
            val newSnapshot = Snapshot<S, T>(delta.items, newCache, current.epoch + 1)
            if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                return
            }
            // CAS failed - another thread modified state, retry with fresh read
        }
    }

    private fun applyMutationToCache(
        cache: Map<Int, CacheEntry<T>>,
        mutation: Mutation,
        newSource: SoftList<S>
    ): Map<Int, CacheEntry<T>> {
        return when (mutation) {
            is Mutation.Insert -> applyInsertToCache(cache, mutation)
            is Mutation.Remove -> applyRemoveToCache(cache, mutation)
            is Mutation.Update -> applyUpdateToCache(cache, mutation, newSource)
            is Mutation.Move -> applyMoveToCache(cache, mutation)
        }
    }

    private fun applyInsertToCache(cache: Map<Int, CacheEntry<T>>, mutation: Mutation.Insert): Map<Int, CacheEntry<T>> {
        return cache.mapKeys { (index, _) ->
            if (index >= mutation.index) index + mutation.count else index
        }
    }

    private fun applyRemoveToCache(cache: Map<Int, CacheEntry<T>>, mutation: Mutation.Remove): Map<Int, CacheEntry<T>> {
        return cache.mapNotNull { (index, value) ->
            when {
                index >= mutation.index + mutation.count -> (index - mutation.count) to value
                index >= mutation.index -> null // Item removed
                else -> index to value
            }
        }.toMap()
    }

    private fun applyUpdateToCache(
        cache: Map<Int, CacheEntry<T>>,
        mutation: Mutation.Update,
        newSource: SoftList<S>
    ): Map<Int, CacheEntry<T>> {
        val result = cache.toMutableMap()
        for (i in mutation.index until (mutation.index + mutation.count)) {
            val existing = result[i]
            val sv = newSource.softGet(i)
            if (existing != null && sv is SoftValue.Present) {
                // Recompute the value but preserve the acquirer count.
                result[i] = existing.copy(value = transform(sv.value))
            }
        }
        return result
    }

    private fun applyMoveToCache(cache: Map<Int, CacheEntry<T>>, mutation: Mutation.Move): Map<Int, CacheEntry<T>> {
        val result = mutableMapOf<Int, CacheEntry<T>>()
        for ((index, entry) in cache) {
            val destination = if (index in mutation.fromIndex until mutation.fromIndex + mutation.count) {
                mutation.toIndex + index - mutation.fromIndex
            } else {
                val removed = if (index >= mutation.fromIndex + mutation.count) index - mutation.count else index
                if (removed >= mutation.toIndex) removed + mutation.count else removed
            }
            result[destination] = entry
        }
        return result
    }

    fun close() {
        while (true) {
            val current = snapshot.load()
            val closed = current.copy(cache = emptyMap(), epoch = current.epoch + 1)
            if (snapshot.compareAndExchange(current, closed) === current) return
        }
    }

    private fun releaseToken(token: Any) {
        while (true) {
            val current = snapshot.load()
            val entry = current.cache.entries.firstOrNull { it.value.token === token } ?: return
            val remaining = if (entry.value.refCount == 1) current.cache - entry.key
                else current.cache + (entry.key to entry.value.copy(refCount = entry.value.refCount - 1))
            if (snapshot.compareAndExchange(current, current.copy(cache = remaining)) === current) return
        }
    }

    /**
     * Returns a [LazyList] view that provides thread-safe lazy access.
     * Items are auto-acquired on access via `get()`.
     * The size and source are captured at creation time for consistency.
     */
    fun asList(): LazyList<T> {
        val currentSnapshot = snapshot.load()
        return LazyListImpl(currentSnapshot.source.size, currentSnapshot.source, currentSnapshot.epoch)
    }

    /**
     * LazyList implementation that auto-acquires items on access.
     *
     * [creationEpoch] is the epoch this view was emitted in. Reads ([get]/[softGet]) are
     * always valid (they resolve against [sourceAtCreation]); lifecycle side effects are
     * honored only while this view is still the current snapshot — a superseded view's
     * `release`/acquire become no-ops so it can never corrupt the live cache.
     */
    private inner class LazyListImpl(
        override val size: Int,
        private val sourceAtCreation: SoftList<S>,
        private val creationEpoch: Int
    ) : AbstractSoftList<T>(), LeasedLazyList<T> {

        override fun acquire(index: Int): SoftValue<T> {
            // Bounds are validated against the per-Delta snapshot this list was created
            // from (not the live snapshot), so an index this Delta advertised as valid is
            // never out of range just because a newer Delta shrank the source.
            if (index < 0 || index >= size) return SoftValue.NotLoaded()
            val sourceValue = sourceAtCreation.softGet(index)
            if (sourceValue !is SoftValue.Present) {
                return (sourceValue as? SoftValue.NotLoaded) ?: SoftValue.NotLoaded()
            }
            // Auto-acquire: increment the refcount, computing the value on first acquire.
            while (true) {
                val current = snapshot.load()
                if (current.epoch != creationEpoch) {
                    // Superseded view: return a stable value without touching the live cache.
                    return SoftValue.Present(transform(sourceValue.value))
                }
                val existing = current.cache[index]
                val newEntry: CacheEntry<T>
                val result: T
                if (existing != null) {
                    newEntry = existing.copy(refCount = existing.refCount + 1)
                    result = existing.value
                } else {
                    val value = transform(sourceValue.value)
                    newEntry = CacheEntry(value, 1)
                    result = value
                }
                val newSnapshot = current.copy(cache = current.cache + (index to newEntry))
                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return SoftValue.Present(result)
                }
                // CAS failed - state changed (delta applied or concurrent acquire); retry.
            }
        }

        override fun acquireItem(index: Int): ItemLease<T>? {
            if (index !in 0 until size) return null
            val sourceValue = sourceAtCreation.softGet(index) as? SoftValue.Present ?: return null
            while (true) {
                val current = snapshot.load()
                if (current.epoch != creationEpoch) return null
                val existing = current.cache[index]
                val entry = existing?.copy(refCount = existing.refCount + 1)
                    ?: CacheEntry(transform(sourceValue.value), 1)
                if (snapshot.compareAndExchange(current, current.copy(cache = current.cache + (index to entry))) === current) {
                    return ItemLease(entry.value) { releaseToken(entry.token) }
                }
            }
        }

        override fun release(index: Int) {
            while (true) {
                val current = snapshot.load()
                if (current.epoch != creationEpoch) return // superseded view: no-op

                val existing = current.cache[index] ?: return // not acquired

                // Decrement; evict only when the last acquirer releases.
                val newCache = if (existing.refCount <= 1) {
                    current.cache - index
                } else {
                    current.cache + (index to existing.copy(refCount = existing.refCount - 1))
                }
                val newSnapshot = current.copy(cache = newCache)

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return
                }
                // CAS failed - retry
            }
        }

        override fun releaseAll() {
            while (true) {
                val current = snapshot.load()
                if (current.epoch != creationEpoch) return // superseded view: no-op

                // Fast path: nothing cached
                if (current.cache.isEmpty()) return

                val newSnapshot = current.copy(cache = emptyMap())

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return
                }
                // CAS failed - retry
            }
        }

        override fun isAcquired(index: Int): Boolean {
            val current = snapshot.load()
            if (current.epoch != creationEpoch) return false // superseded view
            return current.cache.containsKey(index)
        }

        override fun softGet(index: Int): SoftValue<T>? {
            // Pure peek: never mutates the cache or the refcount (so it has no lifecycle
            // side effects), and reads from this Delta's captured source for consistency.
            if (index < 0 || index >= size) return null
            return when (val soft = sourceAtCreation.softGet(index)) {
                is SoftValue.Present -> SoftValue.Present(transform(soft.value))
                is SoftValue.NotLoaded -> soft
                null -> null
            }
        }
    }

    // For testing
    internal fun getCacheSize(): Int = snapshot.load().cache.size
    internal fun isIndexAcquired(index: Int): Boolean = snapshot.load().cache.containsKey(index)
    internal fun refCountOf(index: Int): Int = snapshot.load().cache[index]?.refCount ?: 0
}

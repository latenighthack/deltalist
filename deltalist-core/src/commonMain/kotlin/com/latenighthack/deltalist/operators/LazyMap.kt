package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LazyAccess
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Simple lazy transformation without caching - transforms on every access.
 * Use this when transformations are cheap or when you don't need retention semantics.
 */
fun <T, R> DeltaFlow<T>.lazyMap(transform: (T) -> R): DeltaFlow<R> = flow {
    collect { delta ->
        emit(Delta(
            items = SimpleLazyMapList(delta.items, transform),
            change = delta.change
        ))
    }
}

typealias LazyDeltaFlow<T> = DeltaFlow<LazyAccess<T>>

/**
 * Lazy transformation with acquire/release semantics for memory management.
 *
 * Thread-safe: Uses lock-free atomic operations to handle concurrent access
 * from delta producers (any thread) and UI consumers (typically main thread).
 *
 * Returns a flow of Delta<LazyAccess<R>> where each LazyAccess:
 * - Computes and caches the transformation on first getOrAcquire()
 * - Returns the same cached instance on subsequent getOrAcquire() calls
 * - Tracks position changes automatically across mutations
 * - Allows release() to free the cached value
 *
 * Platform bindings should:
 * - Call getOrAcquire() when an item enters the viewport
 * - Call release() when an item leaves the viewport
 *
 * @param transform The transformation function to apply lazily
 * @return A DeltaFlow of LazyAccess wrappers
 */
fun <T, R> DeltaFlow<T>.lazyMapWithAccess(transform: (T) -> R): LazyDeltaFlow<R> = flow {
    val state = LazyMapState(transform)

    collect { delta ->
        state.applyDelta(delta)
        emit(Delta(state.asList(), delta.change))
    }
}

/**
 * Simple lazy list that transforms on every access (no caching).
 */
internal class SimpleLazyMapList<T, R>(
    private val source: List<T>,
    private val transform: (T) -> R
) : AbstractList<R>() {
    override val size: Int get() = source.size
    override fun get(index: Int): R = transform(source[index])
}

/**
 * Thread-safe state for lazy mapping with acquisition tracking.
 *
 * Uses atomic CAS operations to ensure consistency between:
 * - Delta producers calling applyDelta() (potentially background thread)
 * - UI consumers calling getOrAcquire()/release() (typically main thread)
 *
 * All operations are lock-free and use immutable snapshots.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LazyMapState<S, T>(
    private val transform: (S) -> T
) {
    /**
     * Immutable snapshot of the current state.
     * Updated atomically via CAS.
     */
    private data class Snapshot<S, T>(
        val source: List<S>,
        val cache: Map<Int, T>
    )

    private val snapshot = AtomicReference<Snapshot<S, T>>(Snapshot(emptyList(), emptyMap()))

    // Cache of LazyAccessImpl instances by index for object stability
    private val accessorCache = AtomicReference<Map<Int, LazyAccessImpl>>(emptyMap())

    /**
     * Apply a delta, atomically updating the source and transforming cache indices.
     * Uses CAS loop to handle concurrent modifications from getOrAcquire/release.
     */
    fun applyDelta(delta: Delta<S>) {
        while (true) {
            val current = snapshot.load()
            val newCache: Map<Int, T> = when (val change = delta.change) {
                is Change.Reload -> emptyMap()
                is Change.Mutations -> {
                    var cache = current.cache
                    for (mutation in change.operations) {
                        cache = applyMutationToCache(cache, mutation, delta.items)
                    }
                    cache
                }
            }
            val newSnapshot = Snapshot<S, T>(delta.items, newCache)
            if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                return
            }
            // CAS failed - another thread modified state, retry with fresh read
        }
    }

    private fun applyMutationToCache(
        cache: Map<Int, T>,
        mutation: Mutation,
        newSource: List<S>
    ): Map<Int, T> {
        return when (mutation) {
            is Mutation.Insert -> applyInsertToCache(cache, mutation)
            is Mutation.Remove -> applyRemoveToCache(cache, mutation)
            is Mutation.Update -> applyUpdateToCache(cache, mutation, newSource)
            is Mutation.Move -> applyMoveToCache(cache, mutation)
        }
    }

    private fun applyInsertToCache(cache: Map<Int, T>, mutation: Mutation.Insert): Map<Int, T> {
        return cache.mapKeys { (index, _) ->
            if (index >= mutation.index) index + mutation.count else index
        }
    }

    private fun applyRemoveToCache(cache: Map<Int, T>, mutation: Mutation.Remove): Map<Int, T> {
        return cache.mapNotNull { (index, value) ->
            when {
                index >= mutation.index + mutation.count -> (index - mutation.count) to value
                index >= mutation.index -> null // Item removed
                else -> index to value
            }
        }.toMap()
    }

    private fun applyUpdateToCache(
        cache: Map<Int, T>,
        mutation: Mutation.Update,
        newSource: List<S>
    ): Map<Int, T> {
        val result = cache.toMutableMap()
        for (i in mutation.index until (mutation.index + mutation.count)) {
            if (i in result && i < newSource.size) {
                result[i] = transform(newSource[i])
            }
        }
        return result
    }

    private fun applyMoveToCache(cache: Map<Int, T>, mutation: Mutation.Move): Map<Int, T> {
        val fromIndex = mutation.fromIndex
        val toIndex = mutation.toIndex

        if (fromIndex == toIndex) return cache

        val movedValue = cache[fromIndex]
        val result = mutableMapOf<Int, T>()

        for ((index, value) in cache) {
            if (index == fromIndex) continue // Handle separately

            val newIndex = if (fromIndex < toIndex) {
                when {
                    index > fromIndex && index <= toIndex -> index - 1
                    else -> index
                }
            } else {
                when {
                    index >= toIndex && index < fromIndex -> index + 1
                    else -> index
                }
            }
            result[newIndex] = value
        }

        if (movedValue != null) {
            result[toIndex] = movedValue
        }

        return result
    }

    /**
     * Returns a list view that provides thread-safe LazyAccess wrappers.
     * LazyAccess instances are cached for object stability across list accesses.
     * The size is captured at creation time for consistency during equality comparisons.
     */
    fun asList(): List<LazyAccess<T>> = LazyAccessList(snapshot.load().source.size)

    private inner class LazyAccessList(
        override val size: Int
    ) : AbstractList<LazyAccess<T>>() {

        override fun get(index: Int): LazyAccess<T> {
            // Return cached accessor for object stability
            while (true) {
                val currentCache = accessorCache.load()
                currentCache[index]?.let { return it }

                // Create new accessor and cache it
                val newAccessor = LazyAccessImpl(index)
                val newCache = currentCache + (index to newAccessor)

                if (accessorCache.compareAndExchange(currentCache, newCache) === currentCache) {
                    return newAccessor
                }
                // CAS failed - someone else might have created it, retry
            }
        }
    }

    private inner class LazyAccessImpl(private val index: Int) : LazyAccess<T> {
        /**
         * Gets the transformed value, computing and caching atomically if needed.
         * Uses CAS loop to handle concurrent access.
         */
        override fun getOrAcquire(): T {
            while (true) {
                val current = snapshot.load()

                // Fast path: already cached
                current.cache[index]?.let { return it }

                // Slow path: compute and cache
                if (index >= current.source.size) {
                    throw IndexOutOfBoundsException("Index $index out of bounds for size ${current.source.size}")
                }

                val value = transform(current.source[index])
                val newCache = current.cache + (index to value)
                val newSnapshot = current.copy(cache = newCache)

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return value
                }
                // CAS failed - state changed (delta applied or concurrent acquire)
                // Retry: will either find cached value or recompute with new source
            }
        }

        /**
         * Releases the cached value atomically.
         * Uses CAS loop to handle concurrent access.
         */
        override fun release() {
            while (true) {
                val current = snapshot.load()

                // Fast path: not cached
                if (!current.cache.containsKey(index)) return

                val newCache = current.cache - index
                val newSnapshot = current.copy(cache = newCache)

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return
                }
                // CAS failed - retry
            }
        }

        override val isAcquired: Boolean
            get() = snapshot.load().cache.containsKey(index)
    }

    // For testing
    internal fun getCacheSize(): Int = snapshot.load().cache.size
    internal fun isIndexAcquired(index: Int): Boolean = snapshot.load().cache.containsKey(index)
}

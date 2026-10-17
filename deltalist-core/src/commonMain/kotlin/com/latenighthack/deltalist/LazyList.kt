package com.latenighthack.deltalist

/**
 * A list that supports lazy acquisition and release of items.
 *
 * Platform adapters (RecyclerView, Compose) detect this interface and automatically
 * manage item lifecycle - acquiring items as they enter the viewport and releasing
 * them when they leave.
 *
 * Accessing items via [get] automatically acquires them. Use [release] to free
 * cached values when items leave the viewport, and [releaseAll] during cleanup.
 *
 * Example usage in a Compose adapter:
 * ```kotlin
 * @Composable
 * fun <T> List<T>.rememberItem(index: Int, key: Any): T {
 *     if (this is LazyList<T>) {
 *         DisposableEffect(key) { onDispose { release(index) } }
 *     }
 *     return this[index]
 * }
 * ```
 *
 * Example usage in a RecyclerView adapter:
 * ```kotlin
 * override fun onViewDetachedFromWindow(holder: VH) {
 *     super.onViewDetachedFromWindow(holder)
 *     items.releaseIfLazy(holder.bindingAdapterPosition)
 * }
 * ```
 */
interface LazyList<out T> : SoftList<T> {
    /**
     * Acquires (pins) the item at [index] and returns its current load state, computing
     * and caching the value on first acquire. This is the lifecycle-aware replacement for
     * the old `get(index)` access. Pair every acquire with a [release].
     */
    fun acquire(index: Int): SoftValue<T>

    /**
     * Releases the cached value at the given index, allowing it to be garbage collected.
     *
     * After the last acquirer releases, the next access recomputes the value. Platform
     * bindings should call this when items scroll out of view.
     */
    fun release(index: Int)

    /** Releases all cached values (e.g. when unbinding from lifecycle). */
    fun releaseAll()

    /** Whether the item at the given index currently has an acquired (cached) value. */
    fun isAcquired(index: Int): Boolean
}

/**
 * Acquire-and-pin the item at [index] if this is a [LazyList]; otherwise a plain soft peek.
 * Platform bindings use this to render an item (and pin it for lifecycle) without caring
 * whether the underlying snapshot supports lazy access.
 */
fun <T> SoftList<T>.acquireOrGet(index: Int): SoftValue<T> =
    if (this is LazyList<T>) acquire(index) else (softGet(index) ?: SoftValue.NotLoaded())

/** Releases the item at the given index if this snapshot is a [LazyList]. */
fun <T> SoftList<T>.releaseIfLazy(index: Int) {
    (this as? LazyList<*>)?.release(index)
}

/** Releases all items if this snapshot is a [LazyList]. */
fun <T> SoftList<T>.releaseAllIfLazy() {
    (this as? LazyList<*>)?.releaseAll()
}

/**
 * The contiguous loaded prefix, read through a [LazyList]'s cache, for a binding that keeps
 * every listed item (such as a notification tray) rather than the ones on screen.
 *
 * Each item is acquired once while it's listed: an item already acquired is read without
 * raising its count. Later snapshots therefore return the same object until the item is
 * updated, removed or reloaded, where [softLoadedItems] builds a fresh one on every read.
 * The cache belongs to the binding's own collection, so it goes when the collection ends.
 * A plain soft list returns its loaded items.
 */
fun <T> SoftList<T>.acquireLoadedItems(): List<T> {
    if (this !is LazyList<T>) return softLoadedItems()
    val result = mutableListOf<T>()
    for (i in 0 until size) {
        val wasAcquired = isAcquired(i)
        when (val soft = acquire(i)) {
            is SoftValue.Present -> {
                if (wasAcquired) release(i)
                result.add(soft.value)
            }
            is SoftValue.NotLoaded -> break
        }
    }
    return result
}

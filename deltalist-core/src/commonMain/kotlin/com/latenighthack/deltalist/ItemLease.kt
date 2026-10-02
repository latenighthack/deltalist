package com.latenighthack.deltalist

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One owner's retained item. Release is idempotent and remains valid after snapshot changes. */
@OptIn(ExperimentalAtomicApi::class)
class ItemLease<out T> constructor(val item: T, release: () -> Unit) {
    private val cleanup = AtomicReference<(() -> Unit)?>(release)
    fun release() { cleanup.exchange(null)?.invoke() }
}

/** Optional durable-ownership capability; positional [LazyList] remains source compatible. */
interface LeasedLazyList<out T> : LazyList<T> {
    /** Returns null for unloaded, invalid, or superseded positions; never requests a page. */
    fun acquireItem(index: Int): ItemLease<T>?
}

/** Acquire one owner. Custom legacy LazyLists retain their existing positional release behavior. */
fun <T> SoftList<T>.acquireItemOrGet(index: Int): ItemLease<T>? {
    if (this is LeasedLazyList<T>) return acquireItem(index)
    val value = acquireOrGet(index) as? SoftValue.Present ?: return null
    return ItemLease(value.value) { releaseIfLazy(index) }
}

/** Swift bridge: avoids converting the soft snapshot to an NSArray. */
fun <T> Delta<T>.acquireItemAt(index: Int): ItemLease<T>? = items.acquireItemOrGet(index)

internal fun <S, T> ItemLease<S>.mapItem(transform: (S) -> T): ItemLease<T> = try {
    ItemLease(transform(item)) { release() }
} catch (error: Throwable) {
    release()
    throw error
}

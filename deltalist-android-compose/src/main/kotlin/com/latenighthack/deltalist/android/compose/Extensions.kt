package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.LeasedLazyList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.acquireOrGet

/**
 * Collects a [DeltaList] as Compose state.
 *
 * This is the primary API for using DeltaList with Jetpack Compose. Use this extension
 * to collect the delta and then pass `delta.items` to standard Compose list APIs.
 *
 * Example with LazyColumn:
 * ```kotlin
 * @Composable
 * fun MyList(deltaList: DeltaList<StableItem<MyData>>) {
 *     val delta = deltaList.collectAsDeltaState()
 *
 *     LazyColumn {
 *         items(
 *             items = delta.items,
 *             key = { it.stableId }
 *         ) { stableItem ->
 *             MyItemCard(stableItem.value)
 *         }
 *     }
 * }
 * ```
 *
 * Example with itemsIndexed:
 * ```kotlin
 * @Composable
 * fun IndexedList(deltaList: DeltaList<Item>) {
 *     val delta = deltaList.collectAsDeltaState()
 *
 *     LazyColumn {
 *         itemsIndexed(delta.items, key = { _, item -> item.id }) { index, item ->
 *             ItemRow(index = index, item = item)
 *         }
 *     }
 * }
 * ```
 *
 * @param initial The initial list to use before the first emission. Defaults to empty.
 * @return The current [Delta] containing items and change information.
 */
@Composable
fun <T> DeltaList<T>.collectAsDeltaState(
    initial: List<T> = emptyList()
): Delta<T> {
    val delta by collectAsState(initial = Delta(initial, Change.Reload))
    return delta
}

/**
 * Accesses an item from a list with automatic lazy lifecycle management.
 *
 * When the list is a [LazyList] (e.g., from [lazyMap().withStableIds()]):
 * - The item is acquired on first access
 * - The item is released when the composable leaves composition
 *
 * For regular lists, this simply returns the item at the given index.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun MyList(deltaList: DeltaList<StableItem<MyData>>) {
 *     val delta = deltaList.collectAsDeltaState()
 *
 *     LazyColumn {
 *         items(
 *             items = delta.items,
 *             key = { it.stableId }
 *         ) { stableItem ->
 *             // Automatic lifecycle management for lazy items
 *             val item = delta.items.rememberItem(
 *                 index = delta.items.indexOf(stableItem),
 *                 key = stableItem.stableId
 *             )
 *             MyItemCard(item.value)
 *         }
 *     }
 * }
 * ```
 *
 * Use this helper for lazy items: ordinary reads do not own a composition lifecycle.
 *
 * @param index The index of the item to access
 * @param key A stable key for the item (used for DisposableEffect identity)
 * @return The item at the given index
 */
@Composable
fun <T> SoftList<T>.rememberItem(index: Int, key: Any): T {
    val list = this
    if (list is LeasedLazyList<T>) return rememberLease(list, index, key).item
    val item = remember(key) {
        when (val v = list.acquireOrGet(index)) {
            is SoftValue.Present -> v.value
            is SoftValue.NotLoaded -> throw IndexOutOfBoundsException("Item at $index is not loaded")
        }
    }

    if (list is LazyList<*>) {
        @Suppress("UNCHECKED_CAST")
        val lazy = list as LazyList<T>
        // The DisposableEffect is keyed on `key` (stable identity), so it does NOT restart
        // when the item merely moves to a new index. Capturing the positional `index`
        // directly would then release the wrong slot on disposal. Track the latest index
        // for this key so onDispose releases the item's *current* position.
        val currentIndex = rememberUpdatedState(index)
        DisposableEffect(key) {
            onDispose { lazy.release(currentIndex.value) }
        }
    }

    return item
}

/**
 * Accesses an item from a list by key with automatic lazy lifecycle management.
 *
 * This is a convenience function for accessing items when you have the item
 * but need lifecycle management. Useful in LazyColumn/LazyRow item lambdas.
 *
 * When the list is a [LazyList]:
 * - The item is released when the composable leaves composition
 *
 * Example:
 * ```kotlin
 * LazyColumn {
 *     items(
 *         items = delta.items,
 *         key = { it.stableId }
 *     ) { stableItem ->
 *         // Use rememberLazyItem to ensure proper release
 *         delta.items.rememberLazyItem(stableItem.stableId)
 *         MyItemCard(stableItem.value)
 *     }
 * }
 * ```
 *
 * @param key The stable key for the item
 * @param index The index of the item (optional, for release - uses key for identity)
 */
@Composable
fun <T> SoftList<T>.rememberLazyItem(key: Any, index: Int) {
    val list = this
    if (list is LeasedLazyList<T>) {
        rememberLease(list, index, key)
        return
    }
    if (list is LazyList<*>) {
        @Suppress("UNCHECKED_CAST")
        val lazy = list as LazyList<T>
        // Release the item's current index, not the one captured at first composition
        // (see rememberItem) so a move-while-composed doesn't release the wrong slot.
        val currentIndex = rememberUpdatedState(index)
        DisposableEffect(key) {
            onDispose { lazy.release(currentIndex.value) }
        }
    }
}

// Structural SoftList equality must not suppress acquisition handover to a new snapshot.
private class SnapshotIdentity(val value: Any) {
    override fun equals(other: Any?): Boolean = other is SnapshotIdentity && value === other.value
    override fun hashCode(): Int = 0
}

private class RememberedLease<T>(val lease: ItemLease<T>) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = lease.release()
    override fun onAbandoned() = lease.release()
}

@Composable
private fun <T> rememberLease(list: LeasedLazyList<T>, index: Int, key: Any): ItemLease<T> =
    remember(key, SnapshotIdentity(list), index) {
        RememberedLease(list.acquireItem(index)
            ?: throw IndexOutOfBoundsException("Item at $index is not loaded or snapshot is obsolete"))
    }.lease

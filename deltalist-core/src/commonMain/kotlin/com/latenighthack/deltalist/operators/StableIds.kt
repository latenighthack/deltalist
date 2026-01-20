package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LazyAccess
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.StableItemImpl
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.StableLazyAccessImpl
import kotlinx.coroutines.flow.flow

/**
 * Wraps items with session-stable integer identifiers.
 *
 * The stable IDs follow items as they move through mutations (insert, remove, move).
 * On reload, all IDs are regenerated.
 *
 * Use this at the boundary between deltalist-core and platform bindings to provide
 * stable keys without requiring the underlying data to implement Identifiable.
 *
 * Example:
 * ```
 * val items: DeltaFlow<MyItem> = ...
 * val stableItems: DeltaFlow<StableItem<MyItem>> = items.withStableIds()
 *
 * // In Compose:
 * items(stableItems, key = { it.stableId }) { stableItem ->
 *     MyItemCard(stableItem.value)
 * }
 * ```
 */
fun <T> DeltaFlow<T>.withStableIds(): DeltaFlow<StableItem<T>> = flow {
    val adapter = StableIdAdapter<T>()

    collect { delta ->
        emit(adapter.applyDelta(delta))
    }
}

/**
 * Wraps LazyAccess items with session-stable integer identifiers.
 *
 * Combines lazy acquisition semantics with stable IDs for platform bindings.
 *
 * Example:
 * ```
 * val items: DeltaFlow<Item> = ...
 * val lazyItems = items.lazyMapWithAccess { transform(it) }.withStableLazyIds()
 *
 * // In Compose:
 * items(lazyItems, key = { it.stableId }) { stableLazyAccess ->
 *     val value = stableLazyAccess.getOrAcquire()
 *     DisposableEffect(stableLazyAccess.stableId) {
 *         onDispose { stableLazyAccess.release() }
 *     }
 *     ...
 * }
 * ```
 */
fun <T> DeltaFlow<LazyAccess<T>>.withStableLazyIds(): DeltaFlow<StableLazyAccess<T>> = flow {
    val adapter = StableLazyAccessAdapter<T>()

    collect { delta ->
        emit(adapter.applyDelta(delta))
    }
}

/**
 * Lazy list wrapper that adds stable IDs without accessing source items until needed.
 * Uses idMapping.size as the source of truth to ensure consistency between size and get().
 */
internal class StableItemList<T>(
    private val source: List<T>,
    private val idMapping: List<Int>
) : AbstractList<StableItem<T>>() {
    override val size: Int get() = idMapping.size

    override fun get(index: Int): StableItem<T> =
        StableItemImpl(idMapping[index], source[index])
}

/**
 * Lazy list wrapper for LazyAccess items that adds stable IDs without accessing source items until needed.
 * Uses idMapping.size as the source of truth to ensure consistency between size and get().
 */
internal class StableLazyAccessList<T>(
    private val source: List<LazyAccess<T>>,
    private val idMapping: List<Int>
) : AbstractList<StableLazyAccess<T>>() {
    override val size: Int get() = idMapping.size

    override fun get(index: Int): StableLazyAccess<T> =
        StableLazyAccessImpl(idMapping[index], source[index])
}

/**
 * Maintains a mapping from list indices to stable IDs.
 *
 * The adapter assigns unique integer IDs to items as they enter the list,
 * and tracks their positions as mutations occur. IDs are session-unique
 * (unique within this adapter instance's lifetime).
 */
internal class StableIdAdapter<T> {
    private var nextId = 0
    // Sparse array: index -> stableId
    private var idMapping = mutableListOf<Int>()

    fun applyDelta(delta: Delta<T>): Delta<StableItem<T>> {
        when (val change = delta.change) {
            is Change.Reload -> {
                // On reload, regenerate all IDs
                idMapping.clear()
                repeat(delta.items.size) {
                    idMapping.add(nextId++)
                }
            }
            is Change.Mutations -> {
                for (mutation in change.operations) {
                    applyMutation(mutation)
                }
            }
        }

        // Use lazy wrapper list - items are only accessed when get() is called
        return Delta(StableItemList(delta.items, idMapping.toList()), delta.change)
    }

    private fun applyMutation(mutation: Mutation) {
        when (mutation) {
            is Mutation.Insert -> {
                // Insert new IDs at the given position
                for (i in 0 until mutation.count) {
                    idMapping.add(mutation.index + i, nextId++)
                }
            }
            is Mutation.Remove -> {
                // Remove IDs at the given positions
                for (i in 0 until mutation.count) {
                    idMapping.removeAt(mutation.index)
                }
            }
            is Mutation.Update -> {
                // IDs don't change on update - the item at that position keeps its ID
            }
            is Mutation.Move -> {
                // Move the ID from one position to another
                val id = idMapping.removeAt(mutation.fromIndex)
                idMapping.add(mutation.toIndex, id)
            }
        }
    }
}

/**
 * Adapter for LazyAccess items with stable IDs.
 */
internal class StableLazyAccessAdapter<T> {
    private var nextId = 0
    private var idMapping = mutableListOf<Int>()

    fun applyDelta(delta: Delta<LazyAccess<T>>): Delta<StableLazyAccess<T>> {
        when (val change = delta.change) {
            is Change.Reload -> {
                idMapping.clear()
                repeat(delta.items.size) {
                    idMapping.add(nextId++)
                }
            }
            is Change.Mutations -> {
                for (mutation in change.operations) {
                    applyMutation(mutation)
                }
            }
        }

        // Use lazy wrapper list - items are only accessed when get() is called
        return Delta(StableLazyAccessList(delta.items, idMapping.toList()), delta.change)
    }

    private fun applyMutation(mutation: Mutation) {
        when (mutation) {
            is Mutation.Insert -> {
                for (i in 0 until mutation.count) {
                    idMapping.add(mutation.index + i, nextId++)
                }
            }
            is Mutation.Remove -> {
                for (i in 0 until mutation.count) {
                    idMapping.removeAt(mutation.index)
                }
            }
            is Mutation.Update -> {
                // IDs don't change on update
            }
            is Mutation.Move -> {
                val id = idMapping.removeAt(mutation.fromIndex)
                idMapping.add(mutation.toIndex, id)
            }
        }
    }
}

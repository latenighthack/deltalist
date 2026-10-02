package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.LeasedLazyList
import com.latenighthack.deltalist.acquireItemOrGet
import com.latenighthack.deltalist.mapItem
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.StableItemImpl
import kotlinx.coroutines.flow.flow

/**
 * Wraps items with session-stable integer identifiers.
 *
 * The stable IDs follow items as they move through mutations (insert, remove, move).
 * On reload, all IDs are regenerated.
 *
 * When the source is backed by a [LazyList], the resulting list preserves lazy semantics:
 * - Items are auto-acquired on access
 * - Platform adapters detect [LazyList] and call release() automatically
 *
 * Use this at the boundary between deltalist-core and platform bindings to provide
 * stable keys without requiring the underlying data to carry identity. If your type already
 * implements [com.latenighthack.deltalist.Stable], it satisfies the same binding contract
 * directly and does not need this operator (and won't get reload-regeneration from it).
 *
 * Example:
 * ```
 * val items: DeltaList<MyItem> = ...
 * val stableItems: DeltaList<StableItem<MyItem>> = items.withStableIds()
 *
 * // In Compose:
 * items(stableItems, key = { it.stableId }) { stableItem ->
 *     MyItemCard(stableItem.value)
 * }
 * ```
 *
 * Example with lazy transformation:
 * ```
 * val items: DeltaList<Item> = ...
 * val lazyStableItems = items.lazyMap { transform(it) }.withStableIds()
 *
 * // Platform adapters handle lifecycle automatically
 * items(lazyStableItems, key = { it.stableId }) { stableItem ->
 *     MyCard(stableItem.value)  // auto-acquired, auto-released
 * }
 * ```
 */
fun <T> DeltaList<T>.withStableIds(): DeltaList<StableItem<T>> = flow {
    val adapter = StableIdAdapter<T>()

    collect { delta ->
        emit(adapter.applyDelta(delta))
    }
}

/**
 * Lazy list wrapper that adds stable IDs without accessing source items until needed.
 * Uses idMapping.size as the source of truth to ensure consistency between size and get().
 */
internal class StableItemList<T>(
    private val source: SoftList<T>,
    private val idMapping: List<Int>
) : AbstractSoftList<StableItem<T>>() {
    override val size: Int get() = idMapping.size

    override fun softGet(index: Int): SoftValue<StableItem<T>>? {
        if (index < 0 || index >= size) return null
        return when (val s = source.softGet(index)) {
            is SoftValue.Present -> SoftValue.Present(StableItemImpl(idMapping[index], s.value))
            is SoftValue.NotLoaded -> s
            null -> null
        }
    }
}

/**
 * LazyList-aware wrapper that adds stable IDs while preserving lazy semantics.
 * Implements [LazyList] to allow platform adapters to manage item lifecycle.
 */
internal class StableItemLazyList<T>(
    private val source: LazyList<T>,
    private val idMapping: List<Int>,
    private val isCurrent: () -> Boolean
) : AbstractSoftList<StableItem<T>>(), LeasedLazyList<StableItem<T>> {
    override val size: Int get() = idMapping.size

    override fun acquireItem(index: Int): ItemLease<StableItem<T>>? =
        if (!isCurrent() || index !in idMapping.indices) null
        else source.acquireItemOrGet(index)?.mapItem { StableItemImpl(idMapping[index], it) }

    override fun acquire(index: Int): SoftValue<StableItem<T>> {
        if (index !in idMapping.indices) return SoftValue.NotLoaded()
        if (!isCurrent()) return softGet(index) ?: SoftValue.NotLoaded()
        return when (val s = source.acquire(index)) {
            is SoftValue.Present -> SoftValue.Present(StableItemImpl(idMapping[index], s.value))
            is SoftValue.NotLoaded -> SoftValue.NotLoaded { if (isCurrent()) s.request() }
        }
    }

    override fun softGet(index: Int): SoftValue<StableItem<T>>? {
        if (index < 0 || index >= size) return null
        return when (val s = source.softGet(index)) {
            is SoftValue.Present -> SoftValue.Present(StableItemImpl(idMapping[index], s.value))
            is SoftValue.NotLoaded -> SoftValue.NotLoaded { if (isCurrent()) s.request() }
            null -> null
        }
    }

    override fun release(index: Int) {
        if (isCurrent()) source.release(index)
    }

    override fun releaseAll() {
        if (isCurrent()) source.releaseAll()
    }

    override fun isAcquired(index: Int): Boolean {
        return isCurrent() && source.isAcquired(index)
    }
}

/**
 * Maintains a mapping from list indices to stable IDs.
 *
 * The adapter assigns unique integer IDs to items as they enter the list,
 * and tracks their positions as mutations occur. IDs are session-unique
 * (unique within this adapter instance's lifetime).
 *
 * Automatically detects if the source is a [LazyList] and preserves lazy semantics.
 */
internal class StableIdAdapter<T> {
    private val lifetime = CompositionLifetime()
    // Session-unique, monotonically increasing ids (kept stable across reloads so the
    // platform bindings never reuse a key for a different item). Stays Int because the
    // iOS binding keys NSDiffableDataSource on Int32; overflow needs ~2.1B inserts in a
    // single session, which is not a practical concern for a UI list.
    private var nextId = 0
    // index -> stableId
    private var idMapping = mutableListOf<Int>()

    fun applyDelta(delta: Delta<T>): Delta<StableItem<T>> {
        val isCurrent = lifetime.next()
        val newSize = delta.items.size
        val change = delta.change

        val effectiveChange: Change = when {
            change is Change.Mutations && idMapping.isNotEmpty() -> {
                // Apply to a copy so a bad (out-of-range) mutation can't corrupt state.
                val applied = tryApplyMutations(idMapping, change.operations)
                if (applied != null && applied.size == newSize) {
                    idMapping = applied
                    change
                } else {
                    // Upstream desync: regenerate ids and surface a Reload instead of
                    // throwing into the flow (keeps the stream alive).
                    regenerate(newSize)
                    Change.Reload
                }
            }
            else -> {
                // Reload, or Mutations with no baseline to apply against.
                regenerate(newSize)
                Change.Reload
            }
        }

        // Invariant established above: idMapping.size == delta.items.size, so
        // StableItemList.get/size can never read source out of range.
        val items = delta.items
        val wrappedList: SoftList<StableItem<T>> = if (items is LazyList<*>) {
            @Suppress("UNCHECKED_CAST")
            StableItemLazyList(items as LazyList<T>, idMapping.toList(), isCurrent)
        } else {
            StableItemList(items, idMapping.toList())
        }

        return Delta(wrappedList, effectiveChange)
    }

    private fun regenerate(size: Int) {
        // Fresh ids continuing from the session counter (never reused across reloads).
        idMapping = MutableList(size) { nextId++ }
    }

    /**
     * Applies [ops] to a copy of [base], returning the new mapping, or `null` if any
     * mutation references an out-of-range index (signals an upstream desync).
     */
    private fun tryApplyMutations(base: List<Int>, ops: List<Mutation>): MutableList<Int>? {
        val m = base.toMutableList()
        for (op in ops) {
            when (op) {
                is Mutation.Insert -> {
                    if (op.count < 0 || op.index < 0 || op.index > m.size) return null
                    for (i in 0 until op.count) m.add(op.index + i, nextId++)
                }
                is Mutation.Remove -> {
                    if (op.count < 0 || op.index < 0 || op.index + op.count > m.size) return null
                    repeat(op.count) { m.removeAt(op.index) }
                }
                is Mutation.Update -> {
                    // IDs are unchanged on update; still range-check to catch desync.
                    if (op.count < 0 || op.index < 0 || op.index + op.count > m.size) return null
                }
                is Mutation.Move -> {
                    if (op.fromIndex < 0 || op.fromIndex >= m.size) return null
                    val id = m.removeAt(op.fromIndex)
                    if (op.toIndex < 0 || op.toIndex > m.size) return null
                    m.add(op.toIndex, id)
                }
            }
        }
        return m
    }
}

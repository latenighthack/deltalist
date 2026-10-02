package com.latenighthack.deltalist.android.recyclerview

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.LeasedLazyList
import com.latenighthack.deltalist.acquireItemOrGet
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.acquireOrGet
import com.latenighthack.deltalist.asSoftList
import com.latenighthack.deltalist.releaseAllIfLazy
import com.latenighthack.deltalist.releaseIfLazy
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base adapter for RecyclerView that automatically handles DeltaList updates.
 *
 * Extend this class and implement [onCreateViewHolder] and [onBindViewHolder].
 * Delta mutations are automatically applied as efficient RecyclerView notifications.
 *
 * ## Stable IDs
 * When the item type is [StableItem], stable IDs are automatically enabled and
 * [getItemId] returns the stable ID. Otherwise, override [getItemId] manually
 * and call [setHasStableIds(true)] if you need stable IDs.
 *
 * ## Lazy List Support
 * When the underlying list is a [LazyList] (e.g., from [lazyMap().withStableIds()]),
 * this adapter automatically manages item lifecycle:
 * - Items are acquired when accessed via [getItem]
 * - Items are released when views are detached or recycled
 * - All items are released when [unbind] is called
 *
 * ## View Types
 * Override [getItemViewType] to support multiple view types. Access items via the
 * protected [items] property or [getItem] method.
 *
 * Example:
 * ```kotlin
 * class MyAdapter(deltaList: DeltaList<StableItem<MyData>>) :
 *     DeltaAdapter<StableItem<MyData>, MyViewHolder>(deltaList) {
 *
 *     override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder { ... }
 *
 *     override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
 *         val item = getItem(position)
 *         holder.bind(item.value)
 *     }
 * }
 * ```
 *
 * Example with lazy transformation:
 * ```kotlin
 * val items = source.lazyMap { transform(it) }.withStableIds()
 *
 * class MyAdapter(deltaList: DeltaList<StableItem<TransformedData>>) :
 *     DeltaAdapter<StableItem<TransformedData>, MyViewHolder>(deltaList) {
 *     // ... lifecycle is managed automatically
 * }
 * ```
 */
abstract class DeltaAdapter<T, VH : RecyclerView.ViewHolder>(
    private val deltaList: DeltaList<T>
) : RecyclerView.Adapter<VH>() {

    /**
     * The current list of items. Updated automatically when deltas are received.
     *
     * When backed by a [LazyList], accessing items via index will auto-acquire them.
     */
    protected var items: SoftList<T> = emptyList<T>().asSoftList()
        private set

    private var job: Job? = null
    private var bindingToken: Any? = null
    private data class HeldItem<T>(val index: Int, val lease: ItemLease<T>)
    private val heldItems = mutableMapOf<VH, HeldItem<T>>()
    private val pendingItems = mutableMapOf<Int, ItemLease<T>>()

    private fun releaseOwnedItems() {
        heldItems.values.forEach { it.lease.release() }
        pendingItems.values.forEach { it.release() }
        heldItems.clear()
        pendingItems.clear()
    }

    private fun nextPosition(index: Int, change: Change): Int? {
        var position = index
        if (change is Change.Mutations) for (op in change.operations) {
            when (op) {
                is Mutation.Insert -> if (position >= op.index) position += op.count
                is Mutation.Remove -> when {
                    position in op.index until op.index + op.count -> return null
                    position >= op.index + op.count -> position -= op.count
                }
                is Mutation.Update -> Unit
                is Mutation.Move -> position = if (position in op.fromIndex until op.fromIndex + op.count) {
                    op.toIndex + position - op.fromIndex
                } else {
                    val removed = if (position >= op.fromIndex + op.count) position - op.count else position
                    if (removed >= op.toIndex) removed + op.count else removed
                }
            }
        }
        return position.takeIf { it in 0 until items.size }
    }

    private fun handoverItems(change: Change) {
        val holders = heldItems.mapNotNull { (holder, old) ->
            nextPosition(old.index, change)?.let { index ->
                items.acquireItemOrGet(index)?.let { holder to HeldItem(index, it) }
            }
        }.toMap()
        val pending = pendingItems.keys.mapNotNull { old ->
            nextPosition(old, change)?.let { index -> items.acquireItemOrGet(index)?.let { index to it } }
        }.toMap()
        releaseOwnedItems()
        heldItems.putAll(holders)
        pendingItems.putAll(pending)
    }
    private var stableIdMode: StableIdMode = StableIdMode.Unknown

    private enum class StableIdMode {
        Unknown,
        Stable,
        None
    }

    /**
     * Starts collecting deltas from the DeltaList and applying them to this adapter.
     * Call this in your Activity/Fragment's onCreate or when attaching the adapter.
     *
     * Subclasses can override this to capture the lifecycle owner or perform setup
     * before delta collection starts. Always call super.bind(owner).
     */
    open fun bind(owner: LifecycleOwner) {
        unbind()
        val token = Any()
        bindingToken = token
        job = owner.lifecycleScope.launch {
            try { deltaList.collect { delta -> applyDelta(delta) } }
            finally { if (bindingToken === token) releaseOwnedItems() }
        }
    }

    /**
     * Stops collecting deltas and releases all lazy items.
     * Call this when the adapter is no longer needed.
     */
    fun unbind() {
        job?.cancel()
        job = null
        bindingToken = null
        releaseOwnedItems()
    }

    private fun applyDelta(delta: Delta<T>) {
        val previousCount = items.size
        items = delta.items
        handoverItems(delta.change)

        // Auto-detect stable ID mode on first non-empty delta
        val firstLoaded = (items.softGet(0) as? SoftValue.Present)?.value
        if (stableIdMode == StableIdMode.Unknown && firstLoaded != null) {
            stableIdMode = when (firstLoaded) {
                is Stable -> StableIdMode.Stable
                else -> StableIdMode.None
            }
            if (stableIdMode == StableIdMode.Stable) {
                setHasStableIds(true)
            }
        }

        when (val change = delta.change) {
            is Change.Reload -> notifyDataSetChanged()
            is Change.Mutations -> {
                // Defensive: if the mutation stream is inconsistent with the snapshot's
                // size change (e.g. an upstream desync), fall back to a full reload
                // rather than dispatching notifications RecyclerView would reject with
                // an "Inconsistency detected" crash.
                if (mutationsAreConsistent(change.operations, previousCount, items.size)) {
                    change.operations.forEach { mutation ->
                        when (mutation) {
                            is Mutation.Insert -> notifyItemRangeInserted(mutation.index, mutation.count)
                            is Mutation.Remove -> notifyItemRangeRemoved(mutation.index, mutation.count)
                            is Mutation.Update -> notifyItemRangeChanged(mutation.index, mutation.count)
                            // Moves are single-item per the Change contract (see applyChange).
                            is Mutation.Move -> notifyItemMoved(mutation.fromIndex, mutation.toIndex)
                        }
                    }
                } else {
                    notifyDataSetChanged()
                }
            }
        }

        onItemsChanged()
    }

    /**
     * Called after each delta has been applied and its RecyclerView notifications dispatched.
     * Override to react to content/size changes (e.g. update a header count). The current item
     * count — including unloaded placeholders for paginated lists — is [getItemCount].
     */
    protected open fun onItemsChanged() {}

    /**
     * Simulates the running item count through [operations] (the same sequential,
     * running-index semantics the appliers use), bounds-checking each step. Returns
     * true only if every operation is in range and the final count equals [endCount].
     */
    private fun mutationsAreConsistent(
        operations: List<Mutation>,
        startCount: Int,
        endCount: Int
    ): Boolean {
        var count = startCount
        for (op in operations) {
            when (op) {
                is Mutation.Insert -> {
                    if (op.count < 0 || op.index < 0 || op.index > count) return false
                    count += op.count
                }
                is Mutation.Remove -> {
                    if (op.count < 0 || op.index < 0 || op.index + op.count > count) return false
                    count -= op.count
                }
                is Mutation.Update -> {
                    if (op.count < 0 || op.index < 0 || op.index + op.count > count) return false
                }
                is Mutation.Move -> {
                    // Single-item move in running coordinates; count is unchanged.
                    if (op.count != 1) return false
                    if (op.fromIndex < 0 || op.fromIndex >= count) return false
                    if (op.toIndex < 0 || op.toIndex >= count) return false
                }
            }
        }
        return count == endCount
    }

    override fun getItemCount(): Int = items.size

    /**
     * Returns the item at the given position.
     * This is useful for subclasses in [onBindViewHolder] and for external code
     * that needs to access items (e.g., ItemTouchHelper callbacks).
     *
     * When backed by a [LazyList], this will auto-acquire the item if not already cached.
     *
     * Note: this does NOT trigger pagination fetches. For a paginated/soft list, accessing an
     * unloaded position throws [IndexOutOfBoundsException]. To drive pagination, read the position
     * with [softGetItem] and call [SoftValue.NotLoaded.request] on the placeholder (typically when
     * binding a loading view holder).
     */
    fun getItem(position: Int): T {
        heldItems.values.firstOrNull { it.index == position }?.let { return it.lease.item }
        return pendingItems.getOrPut(position) {
            items.acquireItemOrGet(position)
                ?: throw IndexOutOfBoundsException("Item at $position is not loaded")
        }.item
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val next = pendingItems.remove(position) ?: items.acquireItemOrGet(position)
        val previous = heldItems.remove(holder)
        if (next != null) heldItems[holder] = HeldItem(position, next)
        try { onBindViewHolder(holder, position) }
        finally { previous?.lease?.release() }
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (heldItems[holder]?.index == position) return
        val next = pendingItems.remove(position) ?: items.acquireItemOrGet(position)
        heldItems.remove(holder)?.lease?.release()
        if (next != null) heldItems[holder] = HeldItem(position, next)
    }

    /**
     * Returns the item at the given position as a [SoftValue] without triggering side effects.
     *
     * This is useful for:
     * - Determining view types in [getItemViewType] for paginated lists
     * - Checking if an item is loaded before accessing it
     * - Avoiding accidental fetch triggers during layout passes
     *
     * For regular (non-paginated) lists, this always returns [SoftValue.Present].
     * For paginated lists backed by [SoftList], this may return [SoftValue.NotLoaded].
     *
     * @return [SoftValue.Present] if loaded, [SoftValue.NotLoaded] if not loaded,
     *         or null if the index is out of bounds.
     */
    fun softGetItem(position: Int): SoftValue<T>? = items.softGetOrNull(position)

    /**
     * Returns the stable ID for the item at the given position.
     *
     * When items implement [Stable] (including [StableItem]), this automatically returns their
     * stable ID. Otherwise, returns [RecyclerView.NO_ID] by default.
     * Override this method if you need custom stable ID logic.
     */
    override fun getItemId(position: Int): Long {
        return when (stableIdMode) {
            StableIdMode.Stable ->
                ((items.softGet(position) as? SoftValue.Present)?.value as? Stable)
                    ?.stableId?.toLong() ?: RecyclerView.NO_ID
            else -> RecyclerView.NO_ID
        }
    }

    /**
     * Called when a view is detached from the window.
     * Releases the lazy item at this position to free memory.
     *
     * Subclasses can override but must call super.
     */
    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        heldItems.remove(holder)?.lease?.release()
    }

    /**
     * Called when a view is recycled.
     * Releases the lazy item at this position to free memory.
     *
     * Subclasses can override but must call super.
     */
    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        heldItems.remove(holder)?.lease?.release()
    }
}

package com.latenighthack.deltalist.android.recyclerview

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Abstract adapter that extends [DeltaAdapter] to manage per-item flow collection automatically.
 *
 * This adapter is designed for items that expose observable state via [Flow]. It handles:
 * - Starting flow collection when a ViewHolder is attached to the window
 * - Stopping flow collection when a ViewHolder is detached or recycled
 * - Delivering state updates via the [onItemStateChanged] callback
 *
 * Flow collection is tied to view attachment (not binding) because a ViewHolder can be
 * bound but not visible (e.g., during prefetch). This ensures flows only run for
 * actually visible items.
 *
 * Example:
 * ```kotlin
 * class TickingAdapter(deltaList: DeltaList<TickingItem>) :
 *     FlowDeltaAdapter<TickingItem, Int, TickingViewHolder>(
 *         deltaList,
 *         flowAccessor = { it.tickCount }
 *     ) {
 *
 *     override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickingViewHolder {
 *         return TickingViewHolder(LayoutInflater.from(parent.context)
 *             .inflate(R.layout.item_ticking, parent, false))
 *     }
 *
 *     override fun onBindItem(holder: TickingViewHolder, position: Int, item: TickingItem) {
 *         holder.nameText.text = item.name
 *     }
 *
 *     override fun onItemStateChanged(holder: TickingViewHolder, state: Int) {
 *         holder.tickText.text = "Ticks: $state"
 *     }
 * }
 * ```
 *
 * @param T The item type in the DeltaList
 * @param S The state type emitted by the item's flow
 * @param VH The ViewHolder type
 * @param deltaList The DeltaList to observe
 * @param flowAccessor Function to extract the Flow from an item
 */
abstract class FlowDeltaAdapter<T, S, VH : RecyclerView.ViewHolder>(
    deltaList: DeltaList<T>,
    private val flowAccessor: (T) -> Flow<S>
) : DeltaAdapter<T, VH>(deltaList) {

    /** Configure stable IDs before attaching this adapter to RecyclerView. */
    constructor(
        deltaList: DeltaList<T>,
        flowAccessor: (T) -> Flow<S>,
        stableIds: Boolean,
    ) : this(deltaList, flowAccessor) {
        setHasStableIds(stableIds)
    }

    private var lifecycleOwner: LifecycleOwner? = null

    // Track active flow jobs per ViewHolder
    private val viewHolderJobs = mutableMapOf<VH, Job>()

    /**
     * Called when a ViewHolder is bound to an item. Implement this instead of [onBindViewHolder].
     *
     * Use this to set up static content that doesn't change with flow emissions.
     * Dynamic content that updates from the flow should be set in [onItemStateChanged].
     *
     * @param holder The ViewHolder to bind
     * @param position The position in the list
     * @param item The item at this position
     */
    abstract fun onBindItem(holder: VH, position: Int, item: T)

    /**
     * Called when the item's flow emits a new state value.
     *
     * This is called on the main thread. Update the ViewHolder's views here
     * with the latest state.
     *
     * Note: The holder's position may have changed since binding. Use
     * [RecyclerView.ViewHolder.getBindingAdapterPosition] if you need the current position.
     *
     * @param holder The ViewHolder to update
     * @param state The latest state from the item's flow
     */
    abstract fun onItemStateChanged(holder: VH, state: S)

    /**
     * Called when flow collection starts for a ViewHolder.
     * Override to perform setup when an item becomes visible.
     */
    open fun onItemFlowStarted(holder: VH) {}

    /**
     * Called when flow collection stops for a ViewHolder.
     * Override to perform cleanup when an item is no longer visible.
     */
    open fun onItemFlowStopped(holder: VH) {}

    /**
     * Called when an error occurs during flow collection.
     * Override to handle errors (e.g., show error state in the ViewHolder).
     *
     * By default, errors are silently ignored. The flow collection for this
     * ViewHolder will stop after an error.
     *
     * @param holder The ViewHolder whose flow threw an error
     * @param error The error that occurred
     */
    open fun onItemFlowError(holder: VH, error: Throwable) {}

    override fun bind(owner: LifecycleOwner) {
        lifecycleOwner = owner
        super.bind(owner)
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        onBindItem(holder, position, item)
        // Flow collection starts on attach, not bind
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            startFlowCollection(holder, getItem(position))
        }
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        stopFlowCollection(holder)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        stopFlowCollection(holder)
    }

    private fun startFlowCollection(holder: VH, item: T) {
        viewHolderJobs[holder]?.cancel()

        val owner = lifecycleOwner ?: return
        val flow = flowAccessor(item)

        onItemFlowStarted(holder)
        viewHolderJobs[holder] = owner.lifecycleScope.launch {
            try {
                flow.collectLatest { state ->
                    onItemStateChanged(holder, state)
                }
            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Throwable) {
                onItemFlowError(holder, e)
            }
        }
    }

    private fun stopFlowCollection(holder: VH) {
        viewHolderJobs.remove(holder)?.cancel()
        onItemFlowStopped(holder)
    }
}

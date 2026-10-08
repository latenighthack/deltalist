package com.latenighthack.deltalist.android.notifications

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Translates a stream of [Delta]s into notification post/cancel operations on a
 * [NotificationSink]. This is the only piece of stateful sequencing logic, and it is pure
 * Kotlin (no Android types) so it can be unit-tested directly.
 *
 * Reconciliation is keyed on [Stable.stableId] rather than the raw mutation indices.
 * This yields the same observable tray result as a positional translation (Insert -> post,
 * Update -> re-post same id, Remove -> cancel, Move -> no-op, Reload -> diff) while being
 * robust to multiple mutations batched in a single [Change.Mutations]:
 *  - an id present now but not before -> post (Insert)
 *  - an id present before but not now -> cancel (Remove)
 *  - a surviving id whose value instance changed, or any survivor on Reload -> re-post (Update)
 *  - a surviving id with the same value instance -> no-op (Move / unrelated mutation)
 *
 * Because [com.latenighthack.deltalist.operators.withStableIds] regenerates ids on Reload,
 * a Reload degrades to cancel-old + post-new (a brief tray refresh) rather than in-place
 * updates — an accepted limitation of using session-stable ids as the tray identity.
 */
internal class TrayController<E : Stable, T>(
    private val scope: CoroutineScope,
    private val sink: NotificationSink<E, T>,
    private val grouped: Boolean,
    private val stateAccessor: ((T) -> Flow<Any?>)?,
    private val stateInitial: ((T) -> Any?)?,
    rateLimitPerSecond: Int,
    private val valueOf: (E) -> T,
) {
    private class Entry<T>(
        val stableId: Int,
        var value: T,
        var state: Any?,
        var stateJob: Job?,
    )

    private val samplePeriodMs: Long = (1000L / rateLimitPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)

    // Ordered by current list position; keyed by stableId for O(1) reconciliation.
    private val entries = LinkedHashMap<Int, Entry<T>>()

    fun valueFor(stableId: Int): T? = entries[stableId]?.value

    fun applyDelta(delta: Delta<E>) {
        val forceRepost = delta.change is Change.Reload
        // The notification tray needs every item; notifications are fully loaded, so
        // materialize the soft snapshot's loaded items into an ordinary list.
        val newItems = delta.items.softLoadedItems()
        val newIds = HashSet<Int>(newItems.size).apply { newItems.forEach { add(it.stableId) } }

        // Cancel ids that are gone.
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.stableId !in newIds) {
                entry.stateJob?.cancel()
                sink.cancel(entry.stableId)
                iterator.remove()
            }
        }

        // Re-key in the new order; post inserts and re-post changed survivors.
        val rebuilt = LinkedHashMap<Int, Entry<T>>(newItems.size)
        for (item in newItems) {
            val id = item.stableId
            val value = valueOf(item)
            val existing = entries[id]
            if (existing == null) {
                val entry = Entry(id, value, stateInitial?.invoke(value), null)
                rebuilt[id] = entry
                sink.post(id, entry.value, entry.state)
                startStateJob(entry)
            } else {
                val valueChanged = existing.value !== value
                existing.value = value
                rebuilt[id] = existing
                if (valueChanged || forceRepost) {
                    sink.post(id, existing.value, existing.state)
                }
            }
        }
        entries.clear()
        entries.putAll(rebuilt)

        // Summary content can depend on every member's value and order, not just
        // membership. Refresh it for updates, moves and reloads as well.
        if (grouped) {
            if (newItems.isEmpty()) sink.cancelSummary() else sink.postSummary(newItems)
        }
    }

    /** Cancel everything in the tray and drop all state. */
    fun cancelAll() {
        entries.values.forEach { it.stateJob?.cancel(); sink.cancel(it.stableId) }
        entries.clear()
        if (grouped) sink.cancelSummary()
    }

    /** Stop per-item state collection but leave posted notifications standing. */
    fun stop() {
        entries.values.forEach { it.stateJob?.cancel(); it.stateJob = null }
    }

    @OptIn(FlowPreview::class)
    private fun startStateJob(entry: Entry<T>) {
        val accessor = stateAccessor ?: return
        entry.stateJob = scope.launch {
            accessor(entry.value)
                .sample(samplePeriodMs)
                .collect { newState ->
                    if (newState != entry.state) {
                        entry.state = newState
                        sink.post(entry.stableId, entry.value, newState)
                    }
                }
        }
    }
}

package com.latenighthack.deltalist.android.notifications

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.acquireLoadedItems
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
 * Reconciliation is keyed on each item's tray key rather than the raw mutation indices.
 * This yields the same observable tray result as a positional translation (Insert -> post,
 * Update -> re-post same key, Remove -> cancel, Move -> no-op) while being robust to multiple
 * mutations batched in a single [Change.Mutations]:
 *  - a key present now but not before -> render (Insert)
 *  - a key present before but not now -> cancel (Remove)
 *  - a surviving key whose value [shouldRepost] says changed -> re-post (Update)
 *  - any other survivor -> no-op (Move, Reload, unrelated mutation)
 *
 * Items are read through the list's lazy cache ([acquireLoadedItems]), so a `lazyMap`ped row
 * keeps its object across deltas. A Reload doesn't force a re-post on its own: each survivor
 * still goes through [shouldRepost].
 *
 * An item's first render may adopt a notification already in the tray (see the sink); later
 * renders post. With an [stateAccessor] but no [stateInitial], an item's first render waits
 * for its first state.
 *
 * Because [com.latenighthack.deltalist.operators.withStableIds] regenerates ids on Reload,
 * a stable-id notifier's Reload degrades to cancel-old + post-new.
 */
internal class TrayController<E, T>(
    private val scope: CoroutineScope,
    private val sink: NotificationSink<E, T>,
    private val grouped: Boolean,
    private val keyOf: (E) -> String,
    private val shouldRepost: (old: T, new: T) -> Boolean,
    private val stateAccessor: ((T) -> Flow<Any?>)?,
    private val stateInitial: ((T) -> Any?)?,
    rateLimitPerSecond: Int,
    private val valueOf: (E) -> T,
) {
    private class Entry<T>(
        val key: String,
        var value: T,
        var state: Any?,
        var stateJob: Job?,
        var isRendered: Boolean,
    )

    private val samplePeriodMs: Long = (1000L / rateLimitPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)

    // An item's first render waits for its first state only when there's no initial state.
    private val waitsForFirstState: Boolean = stateAccessor != null && stateInitial == null

    // Ordered by current list position; keyed by tray key for O(1) reconciliation.
    private val entries = LinkedHashMap<String, Entry<T>>()

    fun valueFor(key: String): T? = entries[key]?.value

    fun applyDelta(delta: Delta<E>) {
        // The notification tray needs every item, read through the lazy cache so each
        // listed item keeps its object between deltas.
        val newItems = delta.items.acquireLoadedItems()
        val newKeys = HashSet<String>(newItems.size).apply { newItems.forEach { add(keyOf(it)) } }

        // Cancel keys that are gone.
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.key !in newKeys) {
                entry.stateJob?.cancel()
                sink.cancel(entry.key)
                iterator.remove()
            }
        }

        // Re-key in the new order; render inserts and re-post changed survivors.
        val rebuilt = LinkedHashMap<String, Entry<T>>(newItems.size)
        for (item in newItems) {
            val key = keyOf(item)
            val value = valueOf(item)
            val existing = entries[key]
            if (existing == null) {
                val entry = Entry(key, value, stateInitial?.invoke(value), null, isRendered = false)
                rebuilt[key] = entry
                if (!waitsForFirstState) render(entry)
                startStateJob(entry)
            } else {
                val isReplaced = existing.value !== value
                val isChanged = shouldRepost(existing.value, value)
                val oldState = existing.state
                if (isReplaced) {
                    // Ownership of the observation follows the item, independently of the
                    // caller's value-equality policy for re-posts.
                    existing.stateJob?.cancel()
                    existing.stateJob = null
                    stateInitial?.let { existing.state = it(value) }
                }
                existing.value = value
                rebuilt[key] = existing
                if (existing.isRendered && (isChanged || existing.state != oldState)) {
                    sink.post(key, existing.value, existing.state, isFirst = false)
                }
                if (isReplaced) startStateJob(existing)
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
        entries.values.forEach { it.stateJob?.cancel(); sink.cancel(it.key) }
        entries.clear()
        if (grouped) sink.cancelSummary()
    }

    /** Stop per-item state collection but leave posted notifications standing. */
    fun stop() {
        entries.values.forEach { it.stateJob?.cancel(); it.stateJob = null }
    }

    private fun render(entry: Entry<T>) {
        val isFirst = !entry.isRendered
        entry.isRendered = true
        sink.post(entry.key, entry.value, entry.state, isFirst)
    }

    @OptIn(FlowPreview::class)
    private fun startStateJob(entry: Entry<T>) {
        val accessor = stateAccessor ?: return
        entry.stateJob = scope.launch {
            accessor(entry.value)
                .sample(samplePeriodMs)
                .collect { newState ->
                    if (!entry.isRendered) {
                        entry.state = newState
                        render(entry)
                    } else if (newState != entry.state) {
                        entry.state = newState
                        render(entry)
                    }
                }
        }
    }
}

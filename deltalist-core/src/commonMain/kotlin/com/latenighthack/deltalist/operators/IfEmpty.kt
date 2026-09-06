package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.asSoftList
import kotlinx.coroutines.flow.flow

/**
 * Substitutes a single [placeholder] item whenever the upstream snapshot is empty, so an empty
 * state can be a real list item — scrolling with the list, laid out by the list, bound to its own
 * item view model — instead of an overlay view toggled off item counts.
 *
 * ```kotlin
 * chatRepository.watchChatList()
 *     .lazyMap<Chat, IHomeListItemViewModel> { HomeRoomItemViewModel(it) }
 *     .ifEmpty { emptyRoomsItem }
 * ```
 *
 * ## Where it goes in a chain
 * **After [lazyMap]**, on the already-mapped item stream: the placeholder is an item view model,
 * not a domain model, so mapping it would be wrong. Put it after [asDeltaList] and [filterItems]
 * (a list filtered down to nothing should show the placeholder) and before [sectionedDeltaList],
 * [concat], [header] and [footer] (a header must stay visible while the body shows its
 * placeholder). Applying it per-section before [sectionedDeltaList] gives per-section empty states.
 *
 * ## Emptiness is total size
 * Emptiness is `items.size == 0` — the *total* size, including not-yet-loaded slots. A paginated
 * list with an unloaded tail is therefore NOT empty (it renders loading cells), and a filtered
 * paginated list only becomes empty once its estimate collapses to zero. The operator never calls
 * [SoftList.softGet] on upstream, so it can never force a load; while non-empty it re-emits the
 * upstream [Delta] instance untouched, so a [com.latenighthack.deltalist.LazyList] stays a
 * `LazyList` and pagination's [com.latenighthack.deltalist.SoftValue.NotLoaded] request callbacks
 * survive.
 *
 * ## Placeholder identity
 * [placeholder] is invoked **at most once per collection** and the instance is reused for every
 * empty emission. Binders key per-row state on item identity (iOS's `DeltaRowStateStore` uses
 * `ObjectIdentifier`; [lazyMap] caches by index/identity), so a fresh instance per emission would
 * churn the row and rebind its cell on every upstream tick. Return a fresh instance per invocation
 * so two collectors never share one.
 *
 * ## Emission contract
 * 1:1 with upstream **except** that a tick leaving the list empty when it was already empty is
 * swallowed: the [Delta] would be observationally identical (same instance, same size), and
 * emitting a no-op `Change.Mutations(emptyList())` would be upgraded to a full [Change.Reload] by
 * [concat] / [sectionedDeltaList], which both treat an empty operation list as "reload". An empty
 * upstream that re-emits on every refresh would otherwise reload the whole list each tick.
 *
 * Transitions into and out of the placeholder emit [Change.Reload] — the entire content of the list
 * is swapped, and `Reload` is what every operator here falls back to at a structural discontinuity.
 */
fun <T> DeltaList<T>.ifEmpty(placeholder: () -> T): DeltaList<T> = flow {
    // Built at most once; identity is stable for the life of this collection.
    var placeholderItems: SoftList<T>? = null
    // null = nothing emitted yet; otherwise: did our last emission carry the placeholder?
    var wasEmpty: Boolean? = null

    fun placeholderList(): SoftList<T> =
        placeholderItems ?: listOf(placeholder()).asSoftList().also { placeholderItems = it }

    collect { delta ->
        // Total size only — never softGet()s upstream, so this can't force a load.
        val isEmpty = delta.items.size == 0

        when {
            // First emission is always Reload, matching concat/sectionedDeltaList's first-tick rule.
            wasEmpty == null -> {
                emit(if (isEmpty) Delta(placeholderList(), Change.Reload) else delta.asReload())
                wasEmpty = isEmpty
            }

            // empty -> empty: identical to what we already emitted; swallow it (see KDoc).
            wasEmpty == true && isEmpty -> Unit

            // non-empty -> empty: the whole content swaps for the placeholder row.
            isEmpty -> {
                emit(Delta(placeholderList(), Change.Reload))
                wasEmpty = true
            }

            // empty -> non-empty: the placeholder row swaps for the real content.
            wasEmpty == true -> {
                emit(delta.asReload())
                wasEmpty = false
            }

            // non-empty -> non-empty: pass upstream through untouched. Its mutation coordinates are
            // valid because our last emission carried exactly these items.
            else -> emit(delta)
        }
    }
}

/**
 * Reload-ified view of a delta, preserving the instance when it is already a [Change.Reload] so
 * [concat]'s reference-identity emitter detection behaves exactly as it does without this operator.
 */
private fun <T> Delta<T>.asReload(): Delta<T> =
    if (change is Change.Reload) this else Delta(items, Change.Reload)

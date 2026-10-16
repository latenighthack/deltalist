package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionMutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedDeltaList
import kotlinx.coroutines.flow.flow

/**
 * The sectioned counterpart of [lazyMap]: lazily maps every section's items, with one cache per
 * section, and leaves headers as they are.
 *
 * Each emitted section's `items` is a [com.latenighthack.deltalist.LazyList] with the same
 * acquire/release and lease semantics as [lazyMap]. Platform bindings that acquire rows get the
 * cached row; a pure `softGet` may run [transform] but never pins a value.
 *
 * The caches follow the upstream changes, in running coordinates:
 * - [SectionedChange.Items] moves cached rows with their items inside that section. A
 *   [Mutation.Update] re-runs [transform] for that item's cached row only.
 * - [SectionedChange.Sections] moves each section's cache with its section. Inserted sections
 *   start empty and removed sections drop theirs. [SectionMutation.Update] means the header
 *   changed, so the section keeps its cache, unless its item count changed too.
 * - [SectionedChange.Reload] clears every cache.
 *
 * A change that does not fit the previous emission (for example, a section count that does not
 * match) is delivered as [SectionedChange.Reload], and every cache is cleared. Every emission
 * supersedes the previous one: lifecycle calls on an older snapshot are no-ops, while leases
 * acquired from it can still be released.
 *
 * This is named `lazyMapItems` rather than `lazyMap` because both receivers are a `Flow` on the
 * JVM, so the two functions would have the same signature.
 *
 * ```kotlin
 * val connections: SectionedDeltaList<Header, ConnectionRow> = entries
 *     .groupBy(keySelector = { it.category }, headerMapper = { category, _ -> headerOf(category) })
 *     .lazyMapItems { entry -> ConnectionRow(entry) }
 * ```
 *
 * @param transform Maps one item to its row. Runs when a row is first acquired, again when an
 * [Mutation.Update] arrives for a cached row, and on every pure `softGet`.
 * @return Sections with the same headers, whose items are lazily mapped by [transform].
 */
fun <S, T, R> SectionedDeltaList<S, T>.lazyMapItems(transform: (T) -> R): SectionedDeltaList<S, R> = flow {
    var states = emptyList<LazyMapState<T, R>>()
    var sizes = emptyList<Int>()
    var hasBaseline = false

    fun reload(sections: List<Section<S, T>>) {
        states.forEach { it.close() }
        states = sections.map { section ->
            LazyMapState(transform).also { it.applyDelta(Delta(section.items, Change.Reload)) }
        }
    }

    try {
        collect { delta ->
            val sections = delta.sections
            val change = delta.change
            val moved = when {
                !hasBaseline || change is SectionedChange.Reload -> null
                change is SectionedChange.Sections -> followSections(states, sizes, change.mutations, sections, transform)
                change is SectionedChange.Items -> states.takeIf {
                    change.section in sections.indices && states.size == sections.size &&
                        change.mutations.fit(sizes[change.section], sections[change.section].items.size)
                }?.let { it.map { state -> state to false } }
                else -> null
            }

            val emitted = if (moved == null || moved.size != sections.size) {
                reload(sections)
                SectionedChange.Reload
            } else {
                // Every retained cache moves to this emission's snapshot, so lifecycle calls on
                // the previous snapshot become no-ops even in sections this change left alone.
                moved.forEachIndexed { index, (state, fresh) ->
                    val operations = if (change is SectionedChange.Items && change.section == index) change.mutations else emptyList()
                    if (!fresh) state.applyDelta(Delta(sections[index].items, Change.Mutations(operations)))
                }
                states = moved.map { it.first }
                change
            }
            sizes = sections.map { it.items.size }
            hasBaseline = true
            emit(SectionedDelta(sections.mapIndexed { index, section -> Section(section.header, states[index].asList()) }, emitted))
        }
    } catch (error: Throwable) {
        states.forEach { it.close() }
        throw error
    }
}

/**
 * Applies section mutations to the per-section caches. Returns each section's cache paired with
 * whether it was created for this emission, or null when the mutations do not fit.
 */
private fun <S, T, R> followSections(
    states: List<LazyMapState<T, R>>,
    sizes: List<Int>,
    mutations: List<SectionMutation>,
    sections: List<Section<S, T>>,
    transform: (T) -> R,
): List<Pair<LazyMapState<T, R>, Boolean>>? {
    val running = states.mapIndexedTo(mutableListOf()) { index, state -> Triple(state, sizes[index], false) }
    val removed = mutableListOf<LazyMapState<T, R>>()
    fun fresh(index: Int): Triple<LazyMapState<T, R>, Int, Boolean>? {
        val section = sections.getOrNull(index) ?: return null
        val state = LazyMapState(transform).also { it.applyDelta(Delta(section.items, Change.Reload)) }
        return Triple(state, section.items.size, true)
    }
    fun discard(created: List<Triple<LazyMapState<T, R>, Int, Boolean>>): Nothing? {
        created.filter { it.third }.forEach { it.first.close() }
        return null
    }

    for (mutation in mutations) {
        when (mutation) {
            is SectionMutation.Insert -> {
                if (mutation.count < 0 || mutation.index !in 0..running.size) return discard(running)
                for (offset in 0 until mutation.count) {
                    running.add(mutation.index + offset, fresh(mutation.index + offset) ?: return discard(running))
                }
            }
            is SectionMutation.Remove -> {
                if (mutation.count < 0 || mutation.index < 0 || mutation.index + mutation.count > running.size) return discard(running)
                repeat(mutation.count) {
                    val (state, _, created) = running.removeAt(mutation.index)
                    if (created) state.close() else removed += state
                }
            }
            is SectionMutation.Move -> {
                if (mutation.fromIndex !in running.indices || mutation.toIndex !in running.indices) return discard(running)
                running.add(mutation.toIndex, running.removeAt(mutation.fromIndex))
            }
            is SectionMutation.Update -> {
                if (mutation.index !in running.indices) return discard(running)
                val section = sections.getOrNull(mutation.index) ?: return discard(running)
                if (running[mutation.index].second != section.items.size) {
                    // A header update describes the header only; a resized section starts over.
                    val (state, _, created) = running[mutation.index]
                    if (created) state.close() else removed += state
                    running[mutation.index] = fresh(mutation.index) ?: return discard(running)
                }
            }
        }
    }
    if (running.size != sections.size || running.withIndex().any { (index, entry) -> entry.second != sections[index].items.size }) {
        return discard(running)
    }
    removed.forEach { it.close() }
    return running.map { it.first to it.third }
}

/** Whether these running-coordinate mutations take a list of [from] positions to [to]. */
internal fun List<Mutation>.fit(from: Int, to: Int): Boolean {
    var size = from.toLong()
    for (operation in this) {
        when (operation) {
            is Mutation.Insert -> {
                if (operation.count < 0 || operation.index.toLong() !in 0..size) return false
                size += operation.count
            }
            is Mutation.Remove -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > size) return false
                size -= operation.count
            }
            is Mutation.Update -> {
                if (operation.count < 0 || operation.index < 0 || operation.index.toLong() + operation.count > size) return false
            }
            is Mutation.Move -> {
                if (operation.count < 0 || operation.fromIndex < 0 || operation.toIndex < 0 ||
                    operation.fromIndex.toLong() + operation.count > size ||
                    operation.toIndex.toLong() + operation.count > size) return false
            }
        }
    }
    return size == to.toLong()
}

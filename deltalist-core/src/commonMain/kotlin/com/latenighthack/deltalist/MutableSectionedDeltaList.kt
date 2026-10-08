package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * A conflated mutable holder. Each collector receives Reload initially and after missed
 * publications. Item mutations require a fully loaded section and fail without publishing
 * if any position is unloaded; update callbacks are not invoked in that case.
 * Consecutive changes use running coordinates. Serialize writers; callbacks
 * are not retried. Do not conflate or replay raw deltas downstream.
 */
interface MutableSectionedDeltaList<S, T> : Flow<SectionedDelta<S, T>> {
    val value: List<Section<S, T>>

    // Section-level operations
    fun appendSection(header: S, items: List<T> = emptyList())
    fun insertSection(index: Int, header: S, items: List<T> = emptyList())
    fun removeSection(index: Int)
    fun moveSection(fromIndex: Int, toIndex: Int)
    fun updateSectionHeader(index: Int, header: S)

    // Item-level operations within a section
    fun appendItem(sectionIndex: Int, item: T)
    fun insertItem(sectionIndex: Int, itemIndex: Int, item: T)
    fun removeItem(sectionIndex: Int, itemIndex: Int)
    fun setItem(sectionIndex: Int, itemIndex: Int, item: T)
    fun moveItem(sectionIndex: Int, fromIndex: Int, toIndex: Int)

    // Batch operations
    fun updateSection(index: Int, block: (MutableList<T>) -> Unit)
    fun reload(sections: List<Section<S, T>>)
}

internal class MutableSectionedDeltaListImpl<S, T>(
    initial: List<Section<S, T>>
) : MutableSectionedDeltaList<S, T> {
    private val state = DeltaState(SectionedDelta(initial.publicationSnapshot(), SectionedChange.Reload)) {
        SectionedDelta(it.sections, SectionedChange.Reload)
    }

    override val value: List<Section<S, T>> get() = state.value.sections

    // Section-level operations

    override fun appendSection(header: S, items: List<T>) {
        val current = state.value.sections.toMutableList()
        val index = current.size
        current.add(Section(header, items.toList()))
        state.value = SectionedDelta(current, SectionedChange.Sections(SectionMutation.Insert(index)))
    }

    override fun insertSection(index: Int, header: S, items: List<T>) {
        val current = state.value.sections.toMutableList()
        current.add(index, Section(header, items.toList()))
        state.value = SectionedDelta(current, SectionedChange.Sections(SectionMutation.Insert(index)))
    }

    override fun removeSection(index: Int) {
        val current = state.value.sections.toMutableList()
        current.removeAt(index)
        state.value = SectionedDelta(current, SectionedChange.Sections(SectionMutation.Remove(index)))
    }

    override fun moveSection(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = state.value.sections.toMutableList()
        val section = current.removeAt(fromIndex)
        current.add(toIndex, section)
        state.value = SectionedDelta(current, SectionedChange.Sections(SectionMutation.Move(fromIndex, toIndex)))
    }

    override fun updateSectionHeader(index: Int, header: S) {
        val current = state.value.sections.toMutableList()
        val section = current[index]
        current[index] = section.copy(header = header)
        state.value = SectionedDelta(current, SectionedChange.Sections(SectionMutation.Update(index)))
    }

    // Item-level operations

    override fun appendItem(sectionIndex: Int, item: T) {
        val current = state.value.sections.toMutableList()
        val section = current[sectionIndex]
        val newItems = section.items.requireLoadedItems()
        val itemIndex = newItems.size
        newItems.add(item)
        current[sectionIndex] = section.copy(items = newItems.asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(sectionIndex, Mutation.Insert(itemIndex)))
    }

    override fun insertItem(sectionIndex: Int, itemIndex: Int, item: T) {
        val current = state.value.sections.toMutableList()
        val section = current[sectionIndex]
        val newItems = section.items.requireLoadedItems()
        newItems.add(itemIndex, item)
        current[sectionIndex] = section.copy(items = newItems.asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(sectionIndex, Mutation.Insert(itemIndex)))
    }

    override fun removeItem(sectionIndex: Int, itemIndex: Int) {
        val current = state.value.sections.toMutableList()
        val section = current[sectionIndex]
        val newItems = section.items.requireLoadedItems()
        newItems.removeAt(itemIndex)
        current[sectionIndex] = section.copy(items = newItems.asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(sectionIndex, Mutation.Remove(itemIndex)))
    }

    override fun setItem(sectionIndex: Int, itemIndex: Int, item: T) {
        val current = state.value.sections.toMutableList()
        val section = current[sectionIndex]
        val newItems = section.items.requireLoadedItems()
        newItems[itemIndex] = item
        current[sectionIndex] = section.copy(items = newItems.asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(sectionIndex, Mutation.Update(itemIndex)))
    }

    override fun moveItem(sectionIndex: Int, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = state.value.sections.toMutableList()
        val section = current[sectionIndex]
        val newItems = section.items.requireLoadedItems()
        val item = newItems.removeAt(fromIndex)
        newItems.add(toIndex, item)
        current[sectionIndex] = section.copy(items = newItems.asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(sectionIndex, Mutation.Move(fromIndex, toIndex)))
    }

    // Batch operations

    override fun updateSection(index: Int, block: (MutableList<T>) -> Unit) {
        val current = state.value.sections.toMutableList()
        val section = current[index]
        val tracked = TrackedMutableList(section.items.requireLoadedItems())
        block(tracked)

        val mutations = tracked.toMutations()
        if (mutations.isEmpty()) return

        current[index] = section.copy(items = tracked.toList().asSoftList())
        state.value = SectionedDelta(current, SectionedChange.Items(index, mutations))
    }

    override fun reload(sections: List<Section<S, T>>) {
        state.value = SectionedDelta(sections.publicationSnapshot(), SectionedChange.Reload)
    }

    override suspend fun collect(collector: FlowCollector<SectionedDelta<S, T>>) {
        state.collect(collector)
    }
}

fun <S, T> mutableSectionedDeltaListOf(
    initial: List<Section<S, T>> = emptyList()
): MutableSectionedDeltaList<S, T> = MutableSectionedDeltaListImpl(initial)

// Copy ordinary lists hidden by Section's convenience constructor, preserving lazy snapshots.
private fun <S, T> List<Section<S, T>>.publicationSnapshot(): List<Section<S, T>> = map { section ->
    val items = section.items
    if (items is FullSoftList<T>) section.copy(items = items.snapshot()) else section
}

/** Never turn a partial snapshot into a shorter, fully loaded publication. */
private fun <T> SoftList<T>.requireLoadedItems(): MutableList<T> {
    // Estimated sizes can be enormous even when the very first slot is unloaded.
    val loaded = mutableListOf<T>()
    for (index in 0 until size) {
        val value = softGet(index)
        check(value is SoftValue.Present) {
            "Cannot mutate a section with unloaded items (position $index); reload it with complete items first"
        }
        loaded.add(value.value)
    }
    return loaded
}

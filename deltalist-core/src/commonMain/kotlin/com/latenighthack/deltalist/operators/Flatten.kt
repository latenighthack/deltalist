package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.flow

/**
 * Flattens a sectioned delta flow into a single delta flow.
 *
 * @param header Optional mapper to create a row from section header. If null, no header rows are emitted.
 * @param item Mapper to create a row from each item.
 * @param footer Optional mapper to create a footer row from section header and items.
 */
fun <S, T, R> SectionedDeltaList<S, T>.flatten(
    header: ((S) -> R)? = null,
    item: (T) -> R,
    footer: ((S, List<T>) -> R)? = null
): DeltaList<R> = flow {
    var previousSections: List<Section<S, T>> = emptyList()

    collect { delta ->
        val newSections = delta.sections

        val flattenedItems = FlattenedSectionList(newSections, header, item, footer)

        // If previousSections is empty, we can't translate mutations - treat as Reload
        val change = if (previousSections.isEmpty() && delta.change !is SectionedChange.Reload) {
            Change.Reload
        } else {
            when (val sectionedChange = delta.change) {
                is SectionedChange.Reload -> Change.Reload

                is SectionedChange.Sections -> {
                    val flatMutations = translateSectionMutations(
                        previousSections,
                        newSections,
                        sectionedChange.mutations,
                        header != null,
                        footer != null
                    )
                    if (flatMutations.isEmpty()) Change.Reload else Change.Mutations(flatMutations)
                }

                is SectionedChange.Items -> {
                    val offset = calculateSectionOffset(
                        newSections,
                        sectionedChange.section,
                        header != null,
                        footer != null
                    )
                    val flatMutations = sectionedChange.mutations.map { mutation ->
                        when (mutation) {
                            is Mutation.Insert -> Mutation.Insert(offset + mutation.index, mutation.count)
                            is Mutation.Remove -> Mutation.Remove(offset + mutation.index, mutation.count)
                            is Mutation.Update -> Mutation.Update(offset + mutation.index, mutation.count)
                            is Mutation.Move -> Mutation.Move(
                                offset + mutation.fromIndex,
                                offset + mutation.toIndex,
                                mutation.count
                            )
                        }
                    }
                    Change.Mutations(flatMutations)
                }
            }
        }

        previousSections = newSections
        emit(Delta(flattenedItems, change))
    }
}

/**
 * Flattens sections into just items, no headers or footers.
 */
fun <S, T> SectionedDeltaList<S, T>.flattenItems(): DeltaList<T> = flow {
    var previousSections: List<Section<S, T>> = emptyList()

    collect { delta ->
        val newSections = delta.sections
        val flattenedItems = FlattenedItemsList(newSections)

        // If previousSections is empty, we can't translate mutations - treat as Reload
        val change = if (previousSections.isEmpty() && delta.change !is SectionedChange.Reload) {
            Change.Reload
        } else {
            when (val sectionedChange = delta.change) {
                is SectionedChange.Reload -> Change.Reload

                is SectionedChange.Sections -> {
                    val flatMutations = translateSectionMutationsItemsOnly(
                        previousSections,
                        newSections,
                        sectionedChange.mutations
                    )
                    if (flatMutations.isEmpty()) Change.Reload else Change.Mutations(flatMutations)
                }

                is SectionedChange.Items -> {
                    val offset = previousSections.take(sectionedChange.section).sumOf { it.items.size }
                    val flatMutations = sectionedChange.mutations.map { mutation ->
                        when (mutation) {
                            is Mutation.Insert -> Mutation.Insert(offset + mutation.index, mutation.count)
                            is Mutation.Remove -> Mutation.Remove(offset + mutation.index, mutation.count)
                            is Mutation.Update -> Mutation.Update(offset + mutation.index, mutation.count)
                            is Mutation.Move -> Mutation.Move(
                                offset + mutation.fromIndex,
                                offset + mutation.toIndex,
                                mutation.count
                            )
                        }
                    }
                    Change.Mutations(flatMutations)
                }
            }
        }

        previousSections = newSections
        emit(Delta(flattenedItems, change))
    }
}

/**
 * Lazy list that presents sections as a flat list with headers and items.
 * Implements [SoftList] to propagate soft access from section items.
 */
internal class FlattenedSectionList<S, T, R>(
    private val sections: List<Section<S, T>>,
    private val headerMapper: ((S) -> R)?,
    private val itemMapper: (T) -> R,
    private val footerMapper: ((S, List<T>) -> R)?
) : AbstractList<R>(), SoftList<R> {

    override val size: Int = sections.sumOf { section ->
        var count = section.items.size
        if (headerMapper != null) count++
        if (footerMapper != null) count++
        count
    }

    override fun get(index: Int): R {
        var remaining = index
        for (section in sections) {
            // Header
            if (headerMapper != null) {
                if (remaining == 0) return headerMapper.invoke(section.header)
                remaining--
            }

            // Items
            if (remaining < section.items.size) {
                return itemMapper(section.items[remaining])
            }
            remaining -= section.items.size

            // Footer
            if (footerMapper != null) {
                if (remaining == 0) return footerMapper.invoke(section.header, section.items)
                remaining--
            }
        }
        throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
    }

    override fun softGet(index: Int): SoftValue<R>? {
        if (index < 0 || index >= size) return null

        var remaining = index
        for (section in sections) {
            // Header - always present (not from a SoftList source)
            if (headerMapper != null) {
                if (remaining == 0) return SoftValue.Present(headerMapper.invoke(section.header))
                remaining--
            }

            // Items - may be from a SoftList
            if (remaining < section.items.size) {
                val items = section.items
                return if (items is SoftList<T>) {
                    when (val soft = items.softGet(remaining)) {
                        is SoftValue.Present -> SoftValue.Present(itemMapper(soft.value))
                        is SoftValue.NotLoaded -> soft
                        null -> null
                    }
                } else {
                    SoftValue.Present(itemMapper(items[remaining]))
                }
            }
            remaining -= section.items.size

            // Footer - always present (not from a SoftList source)
            if (footerMapper != null) {
                if (remaining == 0) return SoftValue.Present(footerMapper.invoke(section.header, section.items))
                remaining--
            }
        }
        return null
    }
}

/**
 * Lazy list that presents sections as a flat list of just items.
 * Implements [SoftList] to propagate soft access from section items.
 */
internal class FlattenedItemsList<S, T>(
    private val sections: List<Section<S, T>>
) : AbstractList<T>(), SoftList<T> {

    override val size: Int = sections.sumOf { it.items.size }

    override fun get(index: Int): T {
        var remaining = index
        for (section in sections) {
            if (remaining < section.items.size) {
                return section.items[remaining]
            }
            remaining -= section.items.size
        }
        throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
    }

    override fun softGet(index: Int): SoftValue<T>? {
        if (index < 0 || index >= size) return null

        var remaining = index
        for (section in sections) {
            if (remaining < section.items.size) {
                val items = section.items
                return if (items is SoftList<T>) {
                    items.softGet(remaining)
                } else {
                    SoftValue.Present(items[remaining])
                }
            }
            remaining -= section.items.size
        }
        return null
    }
}

/**
 * Calculate the flat index offset for the start of items in a section.
 */
private fun <S, T> calculateSectionOffset(
    sections: List<Section<S, T>>,
    sectionIndex: Int,
    hasHeader: Boolean,
    hasFooter: Boolean
): Int {
    var offset = 0
    for (i in 0 until sectionIndex) {
        if (hasHeader) offset++
        offset += sections[i].items.size
        if (hasFooter) offset++
    }
    // Add header of the target section
    if (hasHeader) offset++
    return offset
}

/**
 * Translate section mutations to flat mutations with headers/footers.
 */
private fun <S, T> translateSectionMutations(
    previousSections: List<Section<S, T>>,
    newSections: List<Section<S, T>>,
    mutations: List<SectionMutation>,
    hasHeader: Boolean,
    hasFooter: Boolean
): List<Mutation> {
    val result = mutableListOf<Mutation>()
    var workingSections = previousSections.toMutableList()

    for (mutation in mutations) {
        when (mutation) {
            is SectionMutation.Insert -> {
                val offset = calculateFlatOffset(workingSections, mutation.index, hasHeader, hasFooter)
                val insertedSections = newSections.subList(mutation.index, mutation.index + mutation.count)
                val insertCount = insertedSections.sumOf { section ->
                    var count = section.items.size
                    if (hasHeader) count++
                    if (hasFooter) count++
                    count
                }
                if (insertCount > 0) {
                    result.add(Mutation.Insert(offset, insertCount))
                }
                workingSections.addAll(mutation.index, insertedSections)
            }

            is SectionMutation.Remove -> {
                val offset = calculateFlatOffset(workingSections, mutation.index, hasHeader, hasFooter)
                val removeCount = (0 until mutation.count).sumOf { i ->
                    val section = workingSections[mutation.index + i]
                    var count = section.items.size
                    if (hasHeader) count++
                    if (hasFooter) count++
                    count
                }
                if (removeCount > 0) {
                    result.add(Mutation.Remove(offset, removeCount))
                }
                repeat(mutation.count) { workingSections.removeAt(mutation.index) }
            }

            is SectionMutation.Move -> {
                val fromOffset = calculateFlatOffset(workingSections, mutation.fromIndex, hasHeader, hasFooter)
                val section = workingSections[mutation.fromIndex]
                var itemCount = section.items.size
                if (hasHeader) itemCount++
                if (hasFooter) itemCount++

                workingSections.removeAt(mutation.fromIndex)
                result.add(Mutation.Remove(fromOffset, itemCount))

                val toOffset = calculateFlatOffset(workingSections, mutation.toIndex, hasHeader, hasFooter)
                workingSections.add(mutation.toIndex, section)
                result.add(Mutation.Insert(toOffset, itemCount))
            }

            is SectionMutation.Update -> {
                if (hasHeader) {
                    val offset = calculateFlatOffset(workingSections, mutation.index, hasHeader, hasFooter)
                    result.add(Mutation.Update(offset, 1))
                }
            }
        }
    }

    return result
}

/**
 * Translate section mutations to flat mutations (items only, no headers).
 */
private fun <S, T> translateSectionMutationsItemsOnly(
    previousSections: List<Section<S, T>>,
    newSections: List<Section<S, T>>,
    mutations: List<SectionMutation>
): List<Mutation> {
    val result = mutableListOf<Mutation>()
    var workingSections = previousSections.toMutableList()

    for (mutation in mutations) {
        when (mutation) {
            is SectionMutation.Insert -> {
                val offset = workingSections.take(mutation.index).sumOf { it.items.size }
                val insertedSections = newSections.subList(mutation.index, mutation.index + mutation.count)
                val insertCount = insertedSections.sumOf { it.items.size }
                if (insertCount > 0) {
                    result.add(Mutation.Insert(offset, insertCount))
                }
                workingSections.addAll(mutation.index, insertedSections)
            }

            is SectionMutation.Remove -> {
                val offset = workingSections.take(mutation.index).sumOf { it.items.size }
                val removeCount = (0 until mutation.count).sumOf { workingSections[mutation.index + it].items.size }
                if (removeCount > 0) {
                    result.add(Mutation.Remove(offset, removeCount))
                }
                repeat(mutation.count) { workingSections.removeAt(mutation.index) }
            }

            is SectionMutation.Move -> {
                val fromOffset = workingSections.take(mutation.fromIndex).sumOf { it.items.size }
                val section = workingSections[mutation.fromIndex]
                val itemCount = section.items.size

                workingSections.removeAt(mutation.fromIndex)
                if (itemCount > 0) {
                    result.add(Mutation.Remove(fromOffset, itemCount))
                }

                val toOffset = workingSections.take(mutation.toIndex).sumOf { it.items.size }
                workingSections.add(mutation.toIndex, section)
                if (itemCount > 0) {
                    result.add(Mutation.Insert(toOffset, itemCount))
                }
            }

            is SectionMutation.Update -> {
                // No-op for items-only flattening (header change doesn't affect items)
            }
        }
    }

    return result
}

/**
 * Calculate flat offset for a section index.
 */
private fun <S, T> calculateFlatOffset(
    sections: List<Section<S, T>>,
    sectionIndex: Int,
    hasHeader: Boolean,
    hasFooter: Boolean
): Int {
    var offset = 0
    for (i in 0 until sectionIndex) {
        if (hasHeader) offset++
        offset += sections[i].items.size
        if (hasFooter) offset++
    }
    return offset
}

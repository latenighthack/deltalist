package com.latenighthack.deltalist

/**
 * Session-stable identity for the headers and items of one [SectionedDeltaList] collection.
 *
 * Platform bindings use this to key sectioned rows without requiring headers or items to
 * carry identity. Ids follow sections and items through [SectionedChange.Sections] and
 * [SectionedChange.Items] mutations (running coordinates). On [SectionedChange.Reload], a
 * section keeps its id when an equal header was present before; item ids are regenerated,
 * as with [com.latenighthack.deltalist.operators.withStableIds]. Callers that need item
 * identity across reloads should key rows by their own domain id instead.
 *
 * Ids come from one counter, so no section or item id is ever reused for something else.
 *
 * Item values are never read: ids are positional, so unloaded and lazy items stay untouched.
 * Use one instance per collection and apply every emission in order.
 */
class SectionedStableIds {
    private var nextId = 0
    private var headers: List<Any?> = emptyList()
    private var sectionIds = mutableListOf<Int>()
    private var itemIds = mutableListOf<MutableList<Int>>()
    private var hasBaseline = false

    /** Applies [delta] and returns the ids of its sections and items. */
    fun apply(delta: SectionedDelta<*, *>): SectionedIds {
        val sections = delta.sections
        val change = delta.change
        val applied = hasBaseline && when (change) {
            is SectionedChange.Reload -> false
            is SectionedChange.Sections -> applySections(change.mutations, sections)
            is SectionedChange.Items -> applyItems(change.section, change.mutations)
        } && sectionIds.size == sections.size &&
            itemIds.withIndex().all { (index, ids) -> ids.size == sections[index].items.size }

        if (!applied) reload(sections)
        headers = sections.map { it.header }
        hasBaseline = true
        return SectionedIds(sectionIds.toList(), itemIds.map { it.toList() }, this)
    }

    private fun reload(sections: List<Section<*, *>>) {
        val previous = headers.indices.groupByTo(mutableMapOf(), { headers[it] }, { sectionIds[it] })
        sectionIds = sections.mapTo(mutableListOf()) { section ->
            previous[section.header]?.removeFirstOrNull() ?: nextId++
        }
        itemIds = sections.mapTo(mutableListOf()) { section -> MutableList(section.items.size) { nextId++ } }
    }

    private fun applySections(mutations: List<SectionMutation>, sections: List<Section<*, *>>): Boolean {
        val ids = sectionIds.toMutableList()
        val items = itemIds.toMutableList()
        for (mutation in mutations) {
            when (mutation) {
                is SectionMutation.Insert -> {
                    if (mutation.count < 0 || mutation.index !in 0..ids.size) return false
                    for (offset in 0 until mutation.count) {
                        val inserted = sections.getOrNull(mutation.index + offset) ?: return false
                        ids.add(mutation.index + offset, nextId++)
                        items.add(mutation.index + offset, MutableList(inserted.items.size) { nextId++ })
                    }
                }
                is SectionMutation.Remove -> {
                    if (mutation.count < 0 || mutation.index < 0 || mutation.index + mutation.count > ids.size) return false
                    repeat(mutation.count) {
                        ids.removeAt(mutation.index)
                        items.removeAt(mutation.index)
                    }
                }
                is SectionMutation.Move -> {
                    if (mutation.fromIndex !in ids.indices || mutation.toIndex !in ids.indices) return false
                    ids.add(mutation.toIndex, ids.removeAt(mutation.fromIndex))
                    items.add(mutation.toIndex, items.removeAt(mutation.fromIndex))
                }
                is SectionMutation.Update -> if (mutation.index !in ids.indices) return false
            }
        }
        sectionIds = ids
        itemIds = items
        return true
    }

    private fun applyItems(section: Int, mutations: List<Mutation>): Boolean {
        val ids = itemIds.getOrNull(section)?.toMutableList() ?: return false
        for (op in mutations) {
            when (op) {
                is Mutation.Insert -> {
                    if (op.count < 0 || op.index !in 0..ids.size) return false
                    for (offset in 0 until op.count) ids.add(op.index + offset, nextId++)
                }
                is Mutation.Remove -> {
                    if (op.count < 0 || op.index < 0 || op.index + op.count > ids.size) return false
                    repeat(op.count) { ids.removeAt(op.index) }
                }
                is Mutation.Update -> {
                    if (op.count < 0 || op.index < 0 || op.index + op.count > ids.size) return false
                }
                is Mutation.Move -> repeat(op.count) { k ->
                    if (op.fromIndex + k !in ids.indices) return false
                    val id = ids.removeAt(op.fromIndex + k)
                    if (op.toIndex + k !in 0..ids.size) return false
                    ids.add(op.toIndex + k, id)
                }
            }
        }
        itemIds[section] = ids
        return true
    }
}

/** Ids for one sectioned emission, produced by [SectionedStableIds.apply]. */
class SectionedIds internal constructor(
    /** One id per section, in section order. */
    val sectionIds: List<Int>,
    private val itemIds: List<List<Int>>,
    internal val tracker: SectionedStableIds,
) {
    /** The id of the section at [sectionIndex]. */
    fun sectionId(sectionIndex: Int): Int = sectionIds[sectionIndex]

    /** The id of the item at [itemIndex] in section [sectionIndex], loaded or not. */
    fun itemId(sectionIndex: Int, itemIndex: Int): Int = itemIds[sectionIndex][itemIndex]
}

/**
 * Swift bridge: the ids of this emission, following the ids returned for the previous emission
 * of the same collection. Pass null for the first emission, then the latest result each time,
 * applying emissions in order. A native binding keeps only the returned object, which belongs to
 * the framework that exported this delta, so it also works across Kotlin frameworks.
 */
fun SectionedDelta<*, *>.trackStableIds(previous: SectionedIds?): SectionedIds =
    (previous?.tracker ?: SectionedStableIds()).apply(this)

/**
 * Swift bridge for a flat list: the ids of this emission as one section, read with
 * `itemId(0, index)`, following the ids returned for the previous emission of the same list. Ids
 * follow items through inserts, removes and moves, stay the same on an update and are regenerated
 * on a reload, as with [com.latenighthack.deltalist.operators.withStableIds]. Every position has an
 * id, loaded or not, and no item is read, so a lazily mapped list builds no rows.
 */
fun Delta<*>.trackStableIds(previous: SectionedIds?): SectionedIds =
    (previous?.tracker ?: SectionedStableIds()).apply(asOneSection())

private fun Delta<*>.asOneSection(): SectionedDelta<Unit, Any?> = SectionedDelta(
    listOf(Section(Unit, items)),
    when (val change = change) {
        is Change.Reload -> SectionedChange.Reload
        is Change.Mutations -> SectionedChange.Items(0, change.operations)
    },
)

package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedIds
import com.latenighthack.deltalist.SectionedStableIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll

@JsModule("react")
@JsNonModule
private external object SectionedReactInterop {
    fun useRef(initial: dynamic): dynamic
    fun useSyncExternalStore(subscribe: dynamic, getSnapshot: dynamic): dynamic
}

/**
 * Mutable controller behind the stable object returned by [useMappedSectionedDeltaList]:
 *
 *   { sections: [{ key, header, items }], revision, visibleRange(fromSection, fromItem, toSection, toItem) }
 *
 * Each section's `items` is the array proxy of [ReactDeltaListController], owned by the section
 * for as long as it stays in the list, so leases and soft loading behave as in the flat hooks.
 * Section keys and default item keys come from [SectionedStableIds] and follow mutations.
 */
internal class ReactSectionedDeltaListController(
    private var headerTransform: (Any?) -> Any?,
    private var itemTransform: (Any?) -> Any?,
) {
    private val controllers = mutableMapOf<Int, ReactDeltaListController>()
    private var order: List<ReactDeltaListController> = emptyList()
    private var headers: List<Any?> = emptyList()
    private var sectionIds: List<Int> = emptyList()

    /** Incremented by every [update]. */
    var revision = 0
        private set

    val handle: dynamic = js("({ sections: [], revision: 0 })")

    init {
        handle.visibleRange = { fromSection: Int, fromItem: Int, toSection: Int, toItem: Int ->
            visibleRange(fromSection, fromItem, toSection, toItem)
        }
    }

    fun updateTransforms(header: (Any?) -> Any?, item: (Any?) -> Any?) {
        if (itemTransform !== item) {
            itemTransform = item
            controllers.values.forEach { it.updateTransform(item) }
        }
        if (headerTransform !== header) {
            // A new callback may capture new props while the source snapshot stays the same.
            headerTransform = header
            publish()
        }
    }

    fun update(delta: SectionedDelta<Any?, Any?>, ids: SectionedIds) {
        val retained = ids.sectionIds.toSet()
        controllers.keys.filter { it !in retained }.forEach { controllers.remove(it)?.dispose() }

        order = delta.sections.mapIndexed { index, section ->
            controllers.getOrPut(ids.sectionIds[index]) { ReactDeltaListController(itemTransform) }.also {
                it.update(section.items) { item -> "item:${ids.itemId(index, item)}" }
            }
        }
        headers = delta.sections.map { it.header }
        sectionIds = ids.sectionIds
        revision++
        publish()
    }

    fun dispose() {
        controllers.values.forEach { it.dispose() }
        controllers.clear()
        order = emptyList()
    }

    private fun publish() {
        val sections = js("[]")
        for ((index, controller) in order.withIndex()) {
            val entry: dynamic = js("({})")
            entry.key = "section:${sectionIds[index]}"
            entry.header = headerTransform(headers[index])
            entry.items = controller.proxy
            sections.push(entry)
        }
        handle.sections = sections
        handle.revision = revision
    }

    /** Positions from ([fromSection], [fromItem]) through ([toSection], [toItem]), inclusive. */
    private fun visibleRange(fromSection: Int, fromItem: Int, toSection: Int, toItem: Int) {
        for ((index, controller) in order.withIndex()) {
            when {
                index < fromSection || index > toSection -> controller.visibleRange(0, -1)
                else -> controller.visibleRange(
                    if (index == fromSection) fromItem else 0,
                    if (index == toSection) toItem else Int.MAX_VALUE,
                )
            }
        }
    }
}

/**
 * A SectionedDeltaList as a React external store with one stable [handle]:
 * `{ sections, revision, visibleRange }`. Pass [subscribe] and [getSnapshot] to
 * `useSyncExternalStore`; the snapshot is the handle's `revision`, and `sections` is replaced on
 * every change. Collection follows [ReactFlowStore], as in [ReactDeltaListStore].
 *
 * [headerTransform] maps each header when it is published; [itemTransform] is evaluated only
 * when a loaded item is read. Mapped objects without a `key` receive the tracked item key.
 */
public class ReactSectionedDeltaListStore(
    sectionedDeltaList: Any,
    headerTransform: (Any?) -> Any?,
    itemTransform: (Any?) -> Any?,
    graceMillis: Int = ReactFlowStore.defaultGraceMillis,
) {
    private val controller = ReactSectionedDeltaListController(headerTransform, itemTransform)

    @Suppress("UNCHECKED_CAST")
    private val store = ReactFlowStore(
        initial = controller.revision,
        // Section and item IDs are tracked per collection, as each starts from a full snapshot.
        source = flow {
            val ids = SectionedStableIds()
            emitAll((sectionedDeltaList as Flow<SectionedDelta<Any?, Any?>>).map { delta ->
                controller.update(delta, ids.apply(delta))
                controller.revision
            })
        },
        graceMillis = graceMillis,
        onStop = { controller.dispose() },
    )

    /** The list. Its identity never changes. */
    public val handle: Any get() = controller.handle
    public val subscribe: (listener: () -> Unit) -> () -> Unit get() = store.subscribe
    public val getSnapshot: () -> Int get() = store.getSnapshot

    /** Replaces the transforms; headers are mapped again now, items on their next read. */
    public fun updateTransforms(headerTransform: (Any?) -> Any?, itemTransform: (Any?) -> Any?) {
        controller.updateTransforms(headerTransform, itemTransform)
    }
}

/**
 * React hook exposing a SectionedDeltaList through a [ReactSectionedDeltaListStore] owned by the
 * component. The handle keeps its identity while [sectionedDeltaList] does.
 */
public fun useMappedSectionedDeltaList(
    sectionedDeltaList: Any,
    headerTransform: (Any?) -> Any?,
    itemTransform: (Any?) -> Any?,
): Any {
    val storeRef = SectionedReactInterop.useRef(null)
    val owned = storeRef.current
    if (owned == null || owned.source !== sectionedDeltaList) {
        val created: dynamic = js("({})")
        created.source = sectionedDeltaList
        created.store = ReactSectionedDeltaListStore(sectionedDeltaList, headerTransform, itemTransform)
        storeRef.current = created
    }
    val store = storeRef.current.store.unsafeCast<ReactSectionedDeltaListStore>()
    store.updateTransforms(headerTransform, itemTransform)
    SectionedReactInterop.useSyncExternalStore(store.subscribe, store.getSnapshot)
    return store.handle
}

/** Identity-mapped form used by applications consuming SectionedDeltaList without BaseKit. */
public fun useDelegatedSectionedDeltaList(sectionedDeltaList: Any): Any =
    useMappedSectionedDeltaList(sectionedDeltaList, { it }, { it })

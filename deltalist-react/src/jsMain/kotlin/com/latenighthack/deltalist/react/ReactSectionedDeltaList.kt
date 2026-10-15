package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedIds
import com.latenighthack.deltalist.SectionedStableIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@JsModule("react")
@JsNonModule
private external object SectionedReactInterop {
    fun useRef(initial: dynamic): dynamic
    fun useState(initial: dynamic): Array<dynamic>
    fun useEffect(effect: dynamic, deps: Array<Any?> = definedExternally)
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
    private var revision = 0

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
 * React hook exposing a SectionedDeltaList as one stable object whose `sections` array is
 * replaced, and `revision` incremented, on every emission.
 *
 * [headerTransform] maps each header when it is published; [itemTransform] is evaluated only
 * when a loaded item is read, as in [useMappedDeltaList]. Mapped objects without a `key`
 * receive the tracked item key.
 */
public fun useMappedSectionedDeltaList(
    sectionedDeltaList: Any,
    headerTransform: (Any?) -> Any?,
    itemTransform: (Any?) -> Any?,
): Any {
    val controllerRef = SectionedReactInterop.useRef(null)
    if (controllerRef.current == null) {
        controllerRef.current = ReactSectionedDeltaListController(headerTransform, itemTransform)
    }
    val controller = controllerRef.current.unsafeCast<ReactSectionedDeltaListController>()
    controller.updateTransforms(headerTransform, itemTransform)

    val version = SectionedReactInterop.useState(js("({})"))
    val invalidate = version[1]

    SectionedReactInterop.useEffect({
        var active = true
        val scope = CoroutineScope(SupervisorJob())
        val ids = SectionedStableIds()

        @Suppress("UNCHECKED_CAST")
        val flow = sectionedDeltaList as Flow<SectionedDelta<Any?, Any?>>
        scope.launch {
            flow.collect { delta ->
                if (active) {
                    controller.update(delta, ids.apply(delta))
                    invalidate(js("({})"))
                }
            }
        }

        val cleanup: () -> Unit = {
            active = false
            scope.cancel()
            controller.dispose()
        }
        cleanup
    }, arrayOf(sectionedDeltaList))

    return controller.handle
}

/** Identity-mapped form used by applications consuming SectionedDeltaList without BaseKit. */
public fun useDelegatedSectionedDeltaList(sectionedDeltaList: Any): Any =
    useMappedSectionedDeltaList(sectionedDeltaList, { it }, { it })

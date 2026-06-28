package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DragState
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.acquireOrGet
import com.latenighthack.deltalist.operators.mapItems
import com.latenighthack.deltalist.softLoadedCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun Item.toJs(): dynamic {
    val obj = js("({})")
    obj.id = id
    obj.title = title
    return obj
}

private fun SectionRow.toJs(): dynamic {
    val obj = js("({})")
    when (this) {
        is SectionRow.Header -> {
            obj.type = "header"
            obj.title = header.title
            obj.color = "#${header.color.toString(16).padStart(8, '0').substring(2)}"
            obj.id = null
        }
        is SectionRow.ItemRow -> {
            obj.type = "item"
            obj.title = item.title
            obj.color = null
            obj.id = item.id
        }
    }
    return obj
}

private fun DragState<Item>.toJs(): dynamic {
    val obj = js("({})")
    when (this) {
        is DragState.Idle -> {
            obj.state = "idle"
            obj.itemTitle = null
            obj.fromIndex = -1
            obj.toIndex = -1
        }
        is DragState.Dragging -> {
            obj.state = "dragging"
            obj.itemTitle = item.title
            obj.fromIndex = fromIndex
            obj.toIndex = previewIndex
        }
        is DragState.Committing -> {
            obj.state = "committing"
            obj.itemTitle = item.title
            obj.fromIndex = fromIndex
            obj.toIndex = toIndex
        }
    }
    return obj
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsListViewModel {
    private val vm = ListViewModel()

    val items: Any = vm.items.mapItems { it.toJs() }

    // Lazy ticking items, mirroring the Android/iOS adapters. Each delta acquires the
    // loaded StableItem<TickingItem>s (which starts/retains their per-item tick loops) and
    // emits plain JS rows { stableId, title, tickCount }, where tickCount is the live
    // StateFlow the React row observes via useFlow. Items that leave the list are stopped.
    val tickingItems: Any = tickingItemsFlow()

    private fun tickingItemsFlow() = flow {
        // stableId -> (current index, acquired item)
        val held = LinkedHashMap<Int, Pair<Int, StableItem<TickingItem>>>()
        var lastList: SoftList<StableItem<TickingItem>>? = null
        try {
            vm.tickingItems.collect { delta ->
                val list = delta.items
                lastList = list
                @Suppress("UNCHECKED_CAST")
                val lazy = list as? LazyList<StableItem<TickingItem>>
                val loaded = list.softLoadedCount()
                val newHeld = LinkedHashMap<Int, Pair<Int, StableItem<TickingItem>>>()
                val rows = ArrayList<Any?>(loaded)
                for (i in 0 until loaded) {
                    val soft = list.acquireOrGet(i)
                    if (soft is SoftValue.Present) {
                        val stable = soft.value
                        val sid = stable.stableId
                        // Persisting items already carry a refcount across deltas; release the
                        // extra acquire so every held item stays at exactly one acquirer.
                        if (held.containsKey(sid)) lazy?.release(i)
                        newHeld[sid] = i to stable
                        val obj: dynamic = js("({})")
                        obj.stableId = sid
                        obj.title = stable.value.item.title
                        obj.tickCount = stable.value.tickCount
                        rows.add(obj)
                    }
                }
                // Stop ticking for items that left the list.
                for ((sid, entry) in held) {
                    if (!newHeld.containsKey(sid)) entry.second.value.stop()
                }
                held.clear()
                held.putAll(newHeld)
                emit(rows.toTypedArray())
            }
        } finally {
            @Suppress("UNCHECKED_CAST")
            val lazy = lastList as? LazyList<StableItem<TickingItem>>
            for ((_, entry) in held) {
                lazy?.release(entry.first)
                entry.second.value.stop()
            }
        }
    }

    fun addItem() = vm.addItem()
    fun removeItem(index: Int) = vm.removeItem(index)
    fun insertBefore(index: Int) = vm.insertBefore(index)
    fun insertAfter(index: Int) = vm.insertAfter(index)
    fun batchAdd() = vm.batchAdd()
    fun clear() = vm.clear()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsSectionedListViewModel {
    private val vm = SectionedListViewModel()

    val rows: Any = vm.flattenedSections.mapItems { it.toJs() }

    fun addSection() = vm.addSection()
    fun removeSection(index: Int) = vm.removeSection(index)
    fun addItemToSection(sectionIndex: Int) = vm.addItemToSection(sectionIndex)
    fun removeItemFromSection(sectionIndex: Int, itemIndex: Int) = vm.removeItemFromSection(sectionIndex, itemIndex)
    fun moveSectionUp(index: Int) = vm.moveSection(index, index - 1)
    fun moveSectionDown(index: Int) = vm.moveSection(index, index + 1)
    fun clearSections() = vm.clearSections()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsPaginatedListViewModel {
    private val vm = PaginatedListViewModel()

    // The raw delta flow; useSoftDeltaList exposes its full soft list (placeholders included)
    // so the React rows drive fetches via each placeholder's request(), like iOS/Android.
    val items: Any = vm.paginatedNumbers
    val loadingDirection: Any = vm.paginatedLoadingDirection.map { it?.name?.lowercase() }
    val loadedCount: Any = vm.paginatedLoadedCount
    val excludeDivisors: Any = vm.excludeDivisors.map { it.toTypedArray() }

    fun toggleDivisorFilter(divisor: Int) = vm.toggleDivisorFilter(divisor)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsBottomPaginatedListViewModel {
    private val vm = BottomPaginatedListViewModel()

    // Bottom-anchored ("chat-style") paginated soft list. useSoftDeltaList exposes the full soft
    // list (per-item placeholders included), so the React rows drive the BEFORE fetch via each
    // placeholder's request() as the user scrolls up, mirroring iOS/Android.
    val messages: Any = vm.messages
    val loadingDirection: Any = vm.loadingDirection.map { it?.name?.lowercase() }
    val loadedCount: Any = vm.loadedCount
    val excludeDivisors: Any = vm.excludeDivisors.map { it.toTypedArray() }

    fun addAtTop() = vm.addAtTop()
    fun addAtBottom() = vm.addAtBottom()
    fun toggleDivisorFilter(divisor: Int) = vm.toggleDivisorFilter(divisor)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsDragDropViewModel {
    private val vm = DragDropViewModel()
    private val scope = CoroutineScope(SupervisorJob())

    val items: Any = vm.items.mapItems { it.toJs() }
    val dragState: Any = vm.items.dragState.map { it.toJs() }

    fun addItem() = vm.addItem()
    fun addPinnedItem() = vm.addPinnedItem()
    fun clear() = vm.clear()
    fun reset() = vm.reset()

    fun beginDrag(index: Int): Boolean = vm.items.beginDrag(index)
    fun updateDragPreview(toIndex: Int) = vm.items.updateDragPreview(toIndex)
    fun commitDrag() {
        scope.launch { vm.items.commitDrag() }
    }
    fun cancelDrag() = vm.items.cancelDrag()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsDemoApp {
    val listViewModel = JsListViewModel()
    val sectionedListViewModel = JsSectionedListViewModel()
    val paginatedListViewModel = JsPaginatedListViewModel()
    val bottomPaginatedListViewModel = JsBottomPaginatedListViewModel()
    val dragDropViewModel = JsDragDropViewModel()
}

package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.mutableDeltaListOf
import com.latenighthack.deltalist.operators.lazyMap
import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ListViewModel {
    private val _items = mutableDeltaListOf<Item>()
    val items: DeltaList<Item> = _items

    private val tickingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // New clean API: lazyMap + withStableIds
    // Type is DeltaList<StableItem<TickingItem>> instead of DeltaList<StableLazyAccess<TickingItem>>
    // Platform adapters automatically manage lazy lifecycle
    val tickingItems: DeltaList<StableItem<TickingItem>> = _items
        .lazyMap { item -> TickingItem(item, tickingScope) }
        .withStableIds()

    private var counter = 0

    fun addItem() {
        val id = randomUUID()
        _items.append(Item(id, "Item ${++counter}"))
    }

    fun removeItem(index: Int) {
        if (index in 0 until _items.value.size) {
            _items.removeAt(index)
        }
    }

    fun insertBefore(index: Int) {
        val id = randomUUID()
        _items.insert(index, Item(id, "Inserted ${++counter}"))
    }

    fun insertAfter(index: Int) {
        val id = randomUUID()
        _items.insert(index + 1, Item(id, "Inserted ${++counter}"))
    }

    fun batchAdd() {
        _items.update { list ->
            repeat(5) {
                list.add(Item(randomUUID(), "Batch ${++counter}"))
            }
        }
    }

    fun clear() {
        _items.clear()
    }
}

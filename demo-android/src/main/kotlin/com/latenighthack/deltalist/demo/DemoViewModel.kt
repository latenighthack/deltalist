package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LazyAccess
import com.latenighthack.deltalist.mutableDeltaFlowOf
import com.latenighthack.deltalist.operators.lazyMapWithAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

class DemoViewModel {
    private val _items = mutableDeltaFlowOf<Item>()
    val items: DeltaFlow<Item> = _items

    // Scope for ticking items - uses SupervisorJob so individual item cancellation doesn't affect others
    private val tickingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lazy-mapped flow that transforms Items to TickingItems.
     * Each TickingItem starts a timer when acquired (visible) and stops when released.
     * Demonstrates the retention system:
     * - Items in view maintain their tick count
     * - Items scrolled out of view are released and lose their tick count
     * - Items scrolled back in start from tick 0
     */
    val tickingItems: DeltaFlow<LazyAccess<TickingItem>> = _items.lazyMapWithAccess { item ->
        TickingItem(item, tickingScope)
    }

    private var counter = 0

    fun addItem() {
        val id = UUID.randomUUID().toString()
        _items.append(Item(id, "Item ${++counter}"))
    }

    fun removeItem(index: Int) {
        if (index in 0 until _items.value.size) {
            _items.removeAt(index)
        }
    }

    fun insertBefore(index: Int) {
        val id = UUID.randomUUID().toString()
        _items.insert(index, Item(id, "Inserted ${++counter}"))
    }

    fun insertAfter(index: Int) {
        val id = UUID.randomUUID().toString()
        _items.insert(index + 1, Item(id, "Inserted ${++counter}"))
    }

    fun batchAdd() {
        _items.update { list ->
            repeat(5) {
                list.add(Item(UUID.randomUUID().toString(), "Batch ${++counter}"))
            }
        }
    }

    fun clear() {
        _items.clear()
    }
}

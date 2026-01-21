package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.MoveableDeltaFlow
import com.latenighthack.deltalist.mutableDeltaFlowOf
import com.latenighthack.deltalist.moveable
import kotlinx.coroutines.delay
import java.util.UUID

class DragDropViewModel {
    private val _items = mutableDeltaFlowOf(
        (1..10).map { Item(UUID.randomUUID().toString(), "Item $it") }
    )

    /**
     * Moveable flow that simulates persisting moves with a small delay.
     * In a real app, onMove would save to a database or API.
     */
    val items: MoveableDeltaFlow<Item> = _items.moveable(
        canMove = { item, _, _ ->
            // Example: items with "Pinned" in title can't be moved
            !item.title.contains("Pinned")
        },
        onMove = { item, fromIndex, toIndex ->
            // Simulate network/database delay
            delay(300)

            // Persist the move to our source of truth
            _items.move(fromIndex, toIndex)

            true
        }
    )

    private var counter = 10

    fun addItem() {
        val id = UUID.randomUUID().toString()
        _items.append(Item(id, "Item ${++counter}"))
    }

    fun addPinnedItem() {
        val id = UUID.randomUUID().toString()
        _items.insert(0, Item(id, "Pinned ${++counter}"))
    }

    fun clear() {
        _items.clear()
    }

    fun reset() {
        counter = 10
        _items.reload(
            (1..10).map { Item(UUID.randomUUID().toString(), "Item $it") }
        )
    }
}

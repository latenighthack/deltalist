package com.latenighthack.deltalist.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Item(val id: String, val title: String)

/**
 * A wrapper around Item that ticks every 15 seconds while active.
 * Demonstrates the LazyAccess retention system:
 * - When acquired (visible), the tick counter increments
 * - When released (scrolled out), the TickingItem is discarded
 * - When re-acquired, a new TickingItem starts from tick 0
 */
class TickingItem(
    val item: Item,
    private val scope: CoroutineScope
) {
    private val _tickCount = MutableStateFlow(0)
    val tickCount: StateFlow<Int> = _tickCount.asStateFlow()

    private var tickJob: Job? = null

    init {
        startTicking()
    }

    private fun startTicking() {
        tickJob = scope.launch {
            while (true) {
                delay(1_000) // 15 seconds
                _tickCount.value++
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }
}

data class SectionHeader(val title: String, val color: Long)

sealed class SectionRow {
    data class Header(val header: SectionHeader) : SectionRow()
    data class ItemRow(val item: Item) : SectionRow()
}

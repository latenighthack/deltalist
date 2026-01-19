package com.latenighthack.deltalist.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LazyAccess
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun RecyclerViewScreen(viewModel: DemoViewModel) {
    var selectedIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = Modifier.fillMaxSize()) {
        ControlButtons(
            onAdd = { viewModel.addItem() },
            onBatchAdd = { viewModel.batchAdd() },
            onClear = { viewModel.clear() },
            onInsertBefore = if (selectedIndex >= 0) {
                { viewModel.insertBefore(selectedIndex) }
            } else null,
            onInsertAfter = if (selectedIndex >= 0) {
                { viewModel.insertAfter(selectedIndex) }
            } else null,
            onRemove = if (selectedIndex >= 0) {
                {
                    viewModel.removeItem(selectedIndex)
                    selectedIndex = -1
                }
            } else null
        )

        Box(modifier = Modifier.weight(1f)) {
            val lifecycleOwner = LocalLifecycleOwner.current

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = TickingItemAdapter(viewModel.tickingItems) { index ->
                            selectedIndex = if (selectedIndex == index) -1 else index
                        }.also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                },
                update = { recyclerView ->
                    (recyclerView.adapter as? TickingItemAdapter)?.selectedIndex = selectedIndex
                }
            )
        }
    }
}

@Composable
private fun ControlButtons(
    onAdd: () -> Unit,
    onBatchAdd: () -> Unit,
    onClear: () -> Unit,
    onInsertBefore: (() -> Unit)?,
    onInsertAfter: (() -> Unit)?,
    onRemove: (() -> Unit)?
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAdd, modifier = Modifier.padding(4.dp)) {
                Text("Add")
            }
            Button(onClick = onBatchAdd, modifier = Modifier.padding(4.dp)) {
                Text("Batch Add")
            }
            Button(onClick = onClear, modifier = Modifier.padding(4.dp)) {
                Text("Clear")
            }
        }
        if (onInsertBefore != null || onInsertAfter != null || onRemove != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                onInsertBefore?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Insert Before")
                    }
                }
                onInsertAfter?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Insert After")
                    }
                }
                onRemove?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

/**
 * RecyclerView adapter that demonstrates LazyAccess lifecycle management.
 * - Acquires TickingItems when views are attached (visible)
 * - Releases them when views are detached (scrolled out)
 * - Observes tick count and updates the view
 */
private class TickingItemAdapter(
    private val deltaFlow: DeltaFlow<LazyAccess<TickingItem>>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<TickingItemAdapter.TickingItemViewHolder>() {

    private var items: List<LazyAccess<TickingItem>> = emptyList()
    private var collectionJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null

    var selectedIndex: Int = -1
        set(value) {
            val oldIndex = field
            field = value
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (value >= 0) notifyItemChanged(value)
        }

    fun bind(owner: LifecycleOwner) {
        lifecycleOwner = owner
        collectionJob?.cancel()
        collectionJob = owner.lifecycleScope.launch {
            deltaFlow.collect { delta -> applyDelta(delta) }
        }
    }

    fun unbind() {
        collectionJob?.cancel()
        collectionJob = null
        lifecycleOwner = null
    }

    private fun applyDelta(delta: com.latenighthack.deltalist.Delta<LazyAccess<TickingItem>>) {
        items = delta.items
        when (val change = delta.change) {
            is Change.Reload -> notifyDataSetChanged()
            is Change.Mutations -> {
                change.operations.forEach { mutation ->
                    when (mutation) {
                        is Mutation.Insert -> notifyItemRangeInserted(mutation.index, mutation.count)
                        is Mutation.Remove -> notifyItemRangeRemoved(mutation.index, mutation.count)
                        is Mutation.Update -> notifyItemRangeChanged(mutation.index, mutation.count)
                        is Mutation.Move -> {
                            repeat(mutation.count) { i ->
                                notifyItemMoved(mutation.fromIndex + i, mutation.toIndex + i)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickingItemViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
        }
        val titleView = TextView(parent.context).apply {
            textSize = 16f
        }
        val tickView = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(titleView)
        layout.addView(tickView)
        return TickingItemViewHolder(layout, titleView, tickView)
    }

    override fun onBindViewHolder(holder: TickingItemViewHolder, position: Int) {
        val lazyAccess = items[position]
        holder.bind(lazyAccess, position == selectedIndex, onItemClick, lifecycleOwner)
    }

    override fun onViewAttachedToWindow(holder: TickingItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.onAttached()
    }

    override fun onViewDetachedFromWindow(holder: TickingItemViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.onDetached()
    }

    override fun onViewRecycled(holder: TickingItemViewHolder) {
        super.onViewRecycled(holder)
        holder.onRecycled()
    }

    class TickingItemViewHolder(
        view: View,
        private val titleView: TextView,
        private val tickView: TextView
    ) : RecyclerView.ViewHolder(view) {

        private var currentLazyAccess: LazyAccess<TickingItem>? = null
        private var currentTickingItem: TickingItem? = null
        private var tickObserverJob: Job? = null
        private var lifecycleOwner: LifecycleOwner? = null

        fun bind(
            lazyAccess: LazyAccess<TickingItem>,
            isSelected: Boolean,
            onClick: (Int) -> Unit,
            owner: LifecycleOwner?
        ) {
            // Clean up previous item if different
            if (currentLazyAccess !== lazyAccess) {
                releaseCurrentItem()
            }

            currentLazyAccess = lazyAccess
            lifecycleOwner = owner

            // Acquire and display the item
            val tickingItem = lazyAccess.getOrAcquire()
            currentTickingItem = tickingItem

            titleView.text = tickingItem.item.title
            tickView.text = "Ticks: ${tickingItem.tickCount.value} (resets when scrolled out)"

            itemView.isActivated = isSelected
            itemView.setBackgroundColor(if (isSelected) 0x330000FF else 0x00000000)
            itemView.setOnClickListener { onClick(bindingAdapterPosition) }

            // Start observing tick count
            startObservingTicks(tickingItem, owner)
        }

        private fun startObservingTicks(tickingItem: TickingItem, owner: LifecycleOwner?) {
            tickObserverJob?.cancel()
            tickObserverJob = owner?.lifecycleScope?.launch {
                tickingItem.tickCount.collectLatest { count ->
                    tickView.text = "Ticks: $count (resets when scrolled out)"
                }
            }
        }

        fun onAttached() {
            // Item is now visible - ensure it's acquired and observing
            currentLazyAccess?.let { lazyAccess ->
                if (currentTickingItem == null) {
                    val tickingItem = lazyAccess.getOrAcquire()
                    currentTickingItem = tickingItem
                    titleView.text = tickingItem.item.title
                    startObservingTicks(tickingItem, lifecycleOwner)
                }
            }
        }

        fun onDetached() {
            // Item is no longer visible - release it
            releaseCurrentItem()
        }

        fun onRecycled() {
            releaseCurrentItem()
        }

        private fun releaseCurrentItem() {
            tickObserverJob?.cancel()
            tickObserverJob = null

            currentTickingItem?.stop()
            currentTickingItem = null

            currentLazyAccess?.release()
            currentLazyAccess = null
        }
    }
}

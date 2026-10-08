package com.latenighthack.deltalist.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.softLoadedItems
import com.latenighthack.deltalist.android.compose.collectAsDeltaState
import com.latenighthack.deltalist.android.compose.rememberItemState
import com.latenighthack.deltalist.android.recyclerview.FlowDeltaAdapter
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import com.latenighthack.deltalist.releaseIfLazy

class ListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ListScreen()
                }
            }
        }
    }
}

@Composable
private fun ListScreen() {
    val viewModel = remember { ListViewModel() }
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Compose") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("RecyclerView") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ListComposeContent(viewModel)
                1 -> ListRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun ListComposeContent(viewModel: ListViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val delta = viewModel.tickingItems.collectAsDeltaState()
    val originalDelta = viewModel.items.collectAsDeltaState()
    val selectedIndex = originalDelta.items.softLoadedItems().indexOfFirst { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = delta.items.softLoadedItems(),
                key = { stableItem -> stableItem.stableId }
            ) { stableItem ->
                // Clean API: stableItem.value gives us the TickingItem directly
                // LazyList release is handled automatically via DisposableEffect
                LazyTickingItemCard(
                    items = delta.items,
                    stableItem = stableItem,
                    isSelected = { id -> id == selectedId },
                    onClick = { id ->
                        selectedId = if (selectedId == id) null else id
                    }
                )
            }
        }

        ListControlButtons(
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
                    selectedId = null
                }
            } else null
        )
    }
}

@Composable
private fun LazyTickingItemCard(
    items: SoftList<StableItem<TickingItem>>,
    stableItem: StableItem<TickingItem>,
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit
) {
    val stableId = stableItem.stableId
    // Clean API: access the value directly (auto-acquired by LazyList)
    val tickingItem = stableItem.value

    // Handle release when item leaves composition
    // The LazyList will release the cached transformation
    if (items is LazyList<*>) {
        val index = items.softLoadedItems().indexOf(stableItem)
        DisposableEffect(stableId) {
            onDispose {
                tickingItem.stop()
                items.releaseIfLazy(index)
            }
        }
    } else {
        DisposableEffect(stableId) {
            onDispose {
                tickingItem.stop()
            }
        }
    }

    // Use rememberItemState for automatic flow lifecycle management
    val tickCount = rememberItemState(
        item = tickingItem,
        key = stableId,
        initialValue = 0
    ) { it.tickCount }

    val itemId = tickingItem.item.id
    val isItemSelected = isSelected(itemId)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = { onClick(itemId) })
            .then(
                if (isItemSelected) {
                    Modifier.background(Color.Blue.copy(alpha = 0.2f))
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tickingItem.item.title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Ticks: $tickCount | StableId: $stableId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ListRecyclerViewContent(viewModel: ListViewModel) {
    var selectedIndex by remember { mutableIntStateOf(-1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
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

        ListControlButtons(
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
    }
}

@Composable
private fun ListControlButtons(
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

// RecyclerView Adapter using FlowDeltaAdapter
// Clean API: Uses StableItem<TickingItem> instead of StableLazyAccess<TickingItem>
// Lazy lifecycle is managed automatically by DeltaAdapter base class
private class TickingItemAdapter(
    deltaList: DeltaList<StableItem<TickingItem>>,
    private val onItemClick: (Int) -> Unit
) : FlowDeltaAdapter<StableItem<TickingItem>, Int, TickingItemAdapter.TickingItemViewHolder>(
    deltaList,
    // Clean API: access stableItem.value directly (auto-acquired)
    flowAccessor = { stableItem -> stableItem.value.tickCount },
    stableIds = true,
) {
    // Track ticking items to stop them when flow stops
    private val tickingItems = mutableMapOf<TickingItemViewHolder, TickingItem>()

    var selectedIndex: Int = -1
        set(value) {
            val oldIndex = field
            field = value
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (value >= 0) notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickingItemViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
        }
        val titleView = TextView(parent.context).apply { textSize = 16f }
        val tickView = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(titleView)
        layout.addView(tickView)
        return TickingItemViewHolder(layout, titleView, tickView)
    }

    // Called on bind - set up static content only
    // Clean API: use stableItem.value directly (auto-acquired by LazyList)
    override fun onBindItem(holder: TickingItemViewHolder, position: Int, item: StableItem<TickingItem>) {
        val tickingItem = item.value  // Auto-acquired
        holder.titleView.text = tickingItem.item.title
        holder.tickView.text = "Ticks: ${tickingItem.tickCount.value} | StableId: ${item.stableId}"
        holder.stableId = item.stableId

        val isSelected = position == selectedIndex
        holder.itemView.isActivated = isSelected
        holder.itemView.setBackgroundColor(if (isSelected) 0x330000FF else 0x00000000)
        holder.itemView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    // Called when flow emits - update dynamic content
    override fun onItemStateChanged(holder: TickingItemViewHolder, state: Int) {
        holder.tickView.text = "Ticks: $state | StableId: ${holder.stableId}"
    }

    // Called when flow starts (view attached) - track ticking item for later stop
    override fun onItemFlowStarted(holder: TickingItemViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val stableItem = getItem(position)
            val tickingItem = stableItem.value  // Auto-acquired
            tickingItems[holder] = tickingItem
        }
    }

    // Called when flow stops (view detached/recycled) - stop ticking item
    // Note: LazyList release is handled automatically by DeltaAdapter base class
    override fun onItemFlowStopped(holder: TickingItemViewHolder) {
        tickingItems.remove(holder)?.stop()
    }

    class TickingItemViewHolder(
        view: View,
        val titleView: TextView,
        val tickView: TextView
    ) : RecyclerView.ViewHolder(view) {
        var stableId: Int = 0
    }
}

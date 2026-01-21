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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    val delta by viewModel.tickingItems.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val originalDelta by viewModel.items.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val selectedIndex = originalDelta.items.indexOfFirst { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = delta.items,
                key = { stableLazyAccess -> stableLazyAccess.stableId }
            ) { stableLazyAccess ->
                LazyTickingItemCard(
                    stableLazyAccess = stableLazyAccess,
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
    stableLazyAccess: StableLazyAccess<TickingItem>,
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit
) {
    val tickingItem = stableLazyAccess.getOrAcquire()
    val itemId = tickingItem.item.id
    val stableId = stableLazyAccess.stableId

    DisposableEffect(stableId) {
        onDispose {
            tickingItem.stop()
            stableLazyAccess.release()
        }
    }

    val tickCount by tickingItem.tickCount.collectAsState()
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

// RecyclerView Adapter
private class TickingItemAdapter(
    private val deltaFlow: DeltaList<StableLazyAccess<TickingItem>>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<TickingItemAdapter.TickingItemViewHolder>() {

    private var items: List<StableLazyAccess<TickingItem>> = emptyList()
    private var collectionJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null

    var selectedIndex: Int = -1
        set(value) {
            val oldIndex = field
            field = value
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (value >= 0) notifyItemChanged(value)
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].stableId.toLong()
    }

    fun bind(owner: LifecycleOwner) {
        lifecycleOwner = owner
        collectionJob?.cancel()
        collectionJob = owner.lifecycleScope.launch {
            deltaFlow.collect { delta -> applyDelta(delta) }
        }
    }

    private fun applyDelta(delta: Delta<StableLazyAccess<TickingItem>>) {
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
        val titleView = TextView(parent.context).apply { textSize = 16f }
        val tickView = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(titleView)
        layout.addView(tickView)
        return TickingItemViewHolder(layout, titleView, tickView)
    }

    override fun onBindViewHolder(holder: TickingItemViewHolder, position: Int) {
        val stableLazyAccess = items[position]
        holder.bind(stableLazyAccess, position == selectedIndex, onItemClick, lifecycleOwner)
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

        private var currentStableLazyAccess: StableLazyAccess<TickingItem>? = null
        private var currentTickingItem: TickingItem? = null
        private var tickObserverJob: Job? = null
        private var lifecycleOwner: LifecycleOwner? = null

        fun bind(
            stableLazyAccess: StableLazyAccess<TickingItem>,
            isSelected: Boolean,
            onClick: (Int) -> Unit,
            owner: LifecycleOwner?
        ) {
            if (currentStableLazyAccess !== stableLazyAccess) {
                releaseCurrentItem()
            }

            currentStableLazyAccess = stableLazyAccess
            lifecycleOwner = owner

            val tickingItem = stableLazyAccess.getOrAcquire()
            currentTickingItem = tickingItem

            val stableId = stableLazyAccess.stableId
            titleView.text = tickingItem.item.title
            tickView.text = "Ticks: ${tickingItem.tickCount.value} | StableId: $stableId"

            itemView.isActivated = isSelected
            itemView.setBackgroundColor(if (isSelected) 0x330000FF else 0x00000000)
            itemView.setOnClickListener { onClick(bindingAdapterPosition) }

            startObservingTicks(tickingItem, stableId, owner)
        }

        private fun startObservingTicks(tickingItem: TickingItem, stableId: Int, owner: LifecycleOwner?) {
            tickObserverJob?.cancel()
            tickObserverJob = owner?.lifecycleScope?.launch {
                tickingItem.tickCount.collectLatest { count ->
                    tickView.text = "Ticks: $count | StableId: $stableId"
                }
            }
        }

        fun onAttached() {
            currentStableLazyAccess?.let { stableLazyAccess ->
                if (currentTickingItem == null) {
                    val tickingItem = stableLazyAccess.getOrAcquire()
                    currentTickingItem = tickingItem
                    titleView.text = tickingItem.item.title
                    startObservingTicks(tickingItem, stableLazyAccess.stableId, lifecycleOwner)
                }
            }
        }

        fun onDetached() {
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
            currentStableLazyAccess?.release()
            currentStableLazyAccess = null
        }
    }
}

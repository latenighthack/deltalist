package com.latenighthack.deltalist.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DragState
import com.latenighthack.deltalist.MoveableDeltaFlow
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class DragDropActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DragDropScreen()
                }
            }
        }
    }
}

@Composable
private fun DragDropScreen() {
    val viewModel = remember { DragDropViewModel() }
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
                0 -> DragDropComposeContent(viewModel)
                1 -> DragDropRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun DragDropComposeContent(viewModel: DragDropViewModel) {
    val delta by viewModel.items.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val dragState by viewModel.items.dragState.collectAsState()
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // This is called during drag - update preview
        viewModel.items.updateDragPreview(to.index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar showing drag state
        DragStatusBar(dragState)

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = lazyListState
        ) {
            itemsIndexed(
                items = delta.items,
                key = { _, item -> item.id }
            ) { index, item ->
                ReorderableItem(reorderableLazyListState, key = item.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

                    // Check if this item can be moved
                    val isPinned = item.title.contains("Pinned")

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .shadow(elevation),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isDragging -> MaterialTheme.colorScheme.primaryContainer
                                isPinned -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isPinned) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Drag handle",
                                    modifier = Modifier
                                        .draggableHandle(
                                            onDragStarted = {
                                                viewModel.items.beginDrag(index)
                                            },
                                            onDragStopped = {
                                                scope.launch {
                                                    viewModel.items.commitDrag()
                                                }
                                            }
                                        )
                                        .padding(end = 16.dp)
                                )
                            } else {
                                Box(modifier = Modifier.padding(end = 40.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (isPinned) "Cannot be moved" else "Drag to reorder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        DragDropControlButtons(
            onAdd = { viewModel.addItem() },
            onAddPinned = { viewModel.addPinnedItem() },
            onClear = { viewModel.clear() },
            onReset = { viewModel.reset() }
        )
    }
}

@Composable
private fun DragStatusBar(dragState: DragState<Item>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when (dragState) {
                    is DragState.Idle -> MaterialTheme.colorScheme.surface
                    is DragState.Dragging -> MaterialTheme.colorScheme.primaryContainer
                    is DragState.Committing -> MaterialTheme.colorScheme.tertiaryContainer
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (dragState) {
            is DragState.Idle -> {
                Text(
                    text = "Drag items to reorder",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is DragState.Dragging -> {
                Text(
                    text = "Dragging: ${dragState.item.title} (${dragState.fromIndex} → ${dragState.previewIndex})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is DragState.Committing -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Saving: ${dragState.item.title} (${dragState.fromIndex} → ${dragState.toIndex})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DragDropRecyclerViewContent(viewModel: DragDropViewModel) {
    val dragState by viewModel.items.dragState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        DragStatusBar(dragState)

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        val dragAdapter = DragDropAdapter(viewModel.items)
                        adapter = dragAdapter
                        dragAdapter.bind(lifecycleOwner)

                        // Set up ItemTouchHelper for drag and drop
                        val touchHelper = ItemTouchHelper(
                            DragDropTouchCallback(viewModel.items, lifecycleOwner)
                        )
                        touchHelper.attachToRecyclerView(this)
                    }
                }
            )
        }

        DragDropControlButtons(
            onAdd = { viewModel.addItem() },
            onAddPinned = { viewModel.addPinnedItem() },
            onClear = { viewModel.clear() },
            onReset = { viewModel.reset() }
        )
    }
}

@Composable
private fun DragDropControlButtons(
    onAdd: () -> Unit,
    onAddPinned: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAdd, modifier = Modifier.padding(4.dp)) {
                Text("Add")
            }
            Button(onClick = onAddPinned, modifier = Modifier.padding(4.dp)) {
                Text("Add Pinned")
            }
            Button(onClick = onClear, modifier = Modifier.padding(4.dp)) {
                Text("Clear")
            }
            Button(onClick = onReset, modifier = Modifier.padding(4.dp)) {
                Text("Reset")
            }
        }
    }
}

// RecyclerView Adapter
private class DragDropAdapter(
    private val moveable: MoveableDeltaFlow<Item>
) : RecyclerView.Adapter<DragDropAdapter.ViewHolder>() {

    private var items: List<Item> = emptyList()
    private var collectionJob: Job? = null

    fun bind(owner: LifecycleOwner) {
        collectionJob?.cancel()
        collectionJob = owner.lifecycleScope.launch {
            moveable.collect { delta -> applyDelta(delta) }
        }
    }

    private fun applyDelta(delta: Delta<Item>) {
        items = delta.items
        when (val change = delta.change) {
            is Change.Reload -> notifyDataSetChanged()
            is Change.Mutations -> {
                change.operations.forEach { mutation ->
                    when (mutation) {
                        is Mutation.Insert -> notifyItemRangeInserted(mutation.index, mutation.count)
                        is Mutation.Remove -> notifyItemRangeRemoved(mutation.index, mutation.count)
                        is Mutation.Update -> notifyItemRangeChanged(mutation.index, mutation.count)
                        is Mutation.Move -> notifyItemMoved(mutation.fromIndex, mutation.toIndex)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
        }
        val titleView = TextView(parent.context).apply { textSize = 16f }
        val subtitleView = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(titleView)
        layout.addView(subtitleView)
        return ViewHolder(layout, titleView, subtitleView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    fun getItem(position: Int): Item = items[position]

    class ViewHolder(
        view: View,
        private val titleView: TextView,
        private val subtitleView: TextView
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item) {
            val isPinned = item.title.contains("Pinned")
            titleView.text = item.title
            subtitleView.text = if (isPinned) "Cannot be moved" else "Long press to drag"
            itemView.setBackgroundColor(if (isPinned) 0x20FF0000 else 0x00000000)
        }

        fun setDragging(isDragging: Boolean) {
            itemView.alpha = if (isDragging) 0.7f else 1.0f
            itemView.scaleX = if (isDragging) 1.05f else 1.0f
            itemView.scaleY = if (isDragging) 1.05f else 1.0f
        }
    }
}

// ItemTouchHelper callback for drag and drop
private class DragDropTouchCallback(
    private val moveable: MoveableDeltaFlow<Item>,
    private val lifecycleOwner: LifecycleOwner
) : ItemTouchHelper.Callback() {

    private var dragFromPosition: Int = -1

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val adapter = recyclerView.adapter as? DragDropAdapter ?: return 0
        val item = adapter.getItem(viewHolder.bindingAdapterPosition)

        // Don't allow dragging pinned items
        if (item.title.contains("Pinned")) {
            return makeMovementFlags(0, 0)
        }

        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)

        when (actionState) {
            ItemTouchHelper.ACTION_STATE_DRAG -> {
                viewHolder?.let {
                    dragFromPosition = it.bindingAdapterPosition
                    moveable.beginDrag(dragFromPosition)
                    (it as? DragDropAdapter.ViewHolder)?.setDragging(true)
                }
            }
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val toPosition = target.bindingAdapterPosition
        moveable.updateDragPreview(toPosition)

        // Return true to allow visual feedback, but we're not actually moving items yet
        return true
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        (viewHolder as? DragDropAdapter.ViewHolder)?.setDragging(false)

        // Commit the drag
        lifecycleOwner.lifecycleScope.launch {
            moveable.commitDrag()
        }

        dragFromPosition = -1
    }

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Not used
    }
}

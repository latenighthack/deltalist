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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SectionedListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SectionedListScreen()
                }
            }
        }
    }
}

@Composable
private fun SectionedListScreen() {
    val viewModel = remember { SectionedListViewModel() }
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
                0 -> SectionedComposeContent(viewModel)
                1 -> SectionedRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun SectionedComposeContent(viewModel: SectionedListViewModel) {
    var selectedSectionIndex by remember { mutableIntStateOf(-1) }
    var selectedItemIndex by remember { mutableIntStateOf(-1) }
    val delta by viewModel.flattenedSections.collectAsState(initial = Delta(emptyList(), Change.Reload))

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = delta.items,
                key = { row ->
                    when (row) {
                        is SectionRow.Header -> "header-${row.header.title}"
                        is SectionRow.ItemRow -> "item-${row.item.id}"
                    }
                }
            ) { row ->
                when (row) {
                    is SectionRow.Header -> {
                        val sectionIndex = delta.items.take(delta.items.indexOf(row) + 1)
                            .count { it is SectionRow.Header } - 1
                        SectionHeaderCard(
                            header = row.header,
                            isSelected = selectedSectionIndex == sectionIndex,
                            onClick = {
                                selectedSectionIndex = if (selectedSectionIndex == sectionIndex) -1 else sectionIndex
                                selectedItemIndex = -1
                            }
                        )
                    }
                    is SectionRow.ItemRow -> {
                        val itemIdx = delta.items.indexOf(row)
                        var sectionIdx = -1
                        var itemInSectionIdx = -1
                        var count = 0
                        for ((i, r) in delta.items.withIndex()) {
                            if (r is SectionRow.Header) {
                                sectionIdx++
                                count = 0
                            } else if (i == itemIdx) {
                                itemInSectionIdx = count
                                break
                            } else {
                                count++
                            }
                        }

                        SectionItemCard(
                            item = row.item,
                            isSelected = selectedSectionIndex == sectionIdx && selectedItemIndex == itemInSectionIdx,
                            onClick = {
                                if (selectedSectionIndex == sectionIdx && selectedItemIndex == itemInSectionIdx) {
                                    selectedItemIndex = -1
                                } else {
                                    selectedSectionIndex = sectionIdx
                                    selectedItemIndex = itemInSectionIdx
                                }
                            }
                        )
                    }
                }
            }
        }

        SectionedControlButtons(
            onAddSection = { viewModel.addSection() },
            onRemoveSection = if (selectedSectionIndex >= 0) {
                {
                    viewModel.removeSection(selectedSectionIndex)
                    selectedSectionIndex = -1
                    selectedItemIndex = -1
                }
            } else null,
            onAddItem = if (selectedSectionIndex >= 0) {
                { viewModel.addItemToSection(selectedSectionIndex) }
            } else null,
            onRemoveItem = if (selectedSectionIndex >= 0 && selectedItemIndex >= 0) {
                {
                    viewModel.removeItemFromSection(selectedSectionIndex, selectedItemIndex)
                    selectedItemIndex = -1
                }
            } else null,
            onMoveUp = if (selectedSectionIndex > 0) {
                {
                    viewModel.moveSection(selectedSectionIndex, selectedSectionIndex - 1)
                    selectedSectionIndex--
                }
            } else null,
            onMoveDown = if (selectedSectionIndex >= 0) {
                {
                    viewModel.moveSection(selectedSectionIndex, selectedSectionIndex + 1)
                    selectedSectionIndex++
                }
            } else null,
            onClear = { viewModel.clearSections() }
        )
    }
}

@Composable
private fun SectionHeaderCard(
    header: SectionHeader,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(header.color).copy(alpha = if (isSelected) 0.8f else 0.6f))
                .padding(16.dp)
        ) {
            Text(
                text = header.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun SectionItemCard(
    item: Item,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.background(Color.Blue.copy(alpha = 0.2f))
                else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "ID: ${item.id.take(8)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionedRecyclerViewContent(viewModel: SectionedListViewModel) {
    var selectedSectionIndex by remember { mutableIntStateOf(-1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = SectionedAdapter(viewModel.flattenedSections) { sectionIndex ->
                            selectedSectionIndex = if (selectedSectionIndex == sectionIndex) -1 else sectionIndex
                        }.also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                },
                update = { recyclerView ->
                    (recyclerView.adapter as? SectionedAdapter)?.selectedSectionIndex = selectedSectionIndex
                }
            )
        }

        SectionedControlButtons(
            onAddSection = { viewModel.addSection() },
            onRemoveSection = if (selectedSectionIndex >= 0) {
                {
                    viewModel.removeSection(selectedSectionIndex)
                    selectedSectionIndex = -1
                }
            } else null,
            onAddItem = if (selectedSectionIndex >= 0) {
                { viewModel.addItemToSection(selectedSectionIndex) }
            } else null,
            onRemoveItem = null,
            onMoveUp = if (selectedSectionIndex > 0) {
                {
                    viewModel.moveSection(selectedSectionIndex, selectedSectionIndex - 1)
                    selectedSectionIndex--
                }
            } else null,
            onMoveDown = null,
            onClear = { viewModel.clearSections() }
        )
    }
}

@Composable
private fun SectionedControlButtons(
    onAddSection: () -> Unit,
    onRemoveSection: (() -> Unit)?,
    onAddItem: (() -> Unit)?,
    onRemoveItem: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAddSection, modifier = Modifier.padding(4.dp)) {
                Text("+ Section")
            }
            onRemoveSection?.let {
                Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                    Text("- Section")
                }
            }
            Button(onClick = onClear, modifier = Modifier.padding(4.dp)) {
                Text("Clear")
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            onAddItem?.let {
                Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                    Text("+ Item")
                }
            }
            onRemoveItem?.let {
                Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                    Text("- Item")
                }
            }
            onMoveUp?.let {
                Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                    Text("Move Up")
                }
            }
            onMoveDown?.let {
                Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                    Text("Move Down")
                }
            }
        }
    }
}

// RecyclerView Adapter
private class SectionedAdapter(
    private val deltaFlow: DeltaList<SectionRow>,
    private val onSectionClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<SectionRow> = emptyList()
    private var collectionJob: Job? = null

    var selectedSectionIndex: Int = -1
        set(value) {
            val old = field
            field = value
            items.forEachIndexed { index, row ->
                if (row is SectionRow.Header) {
                    notifyItemChanged(index)
                }
            }
        }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    fun bind(owner: LifecycleOwner) {
        collectionJob?.cancel()
        collectionJob = owner.lifecycleScope.launch {
            deltaFlow.collect { delta -> applyDelta(delta) }
        }
    }

    private fun applyDelta(delta: Delta<SectionRow>) {
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

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SectionRow.Header -> VIEW_TYPE_HEADER
        is SectionRow.ItemRow -> VIEW_TYPE_ITEM
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(32, 24, 32, 24)
                    textSize = 18f
                    setTextColor(0xFFFFFFFF.toInt())
                }
                HeaderViewHolder(view)
            }
            else -> {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(64, 16, 32, 16)
                }
                val titleView = TextView(parent.context).apply { textSize = 14f }
                val idView = TextView(parent.context).apply {
                    textSize = 10f
                    setTextColor(0xFF888888.toInt())
                }
                layout.addView(titleView)
                layout.addView(idView)
                ItemViewHolder(layout, titleView, idView)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is SectionRow.Header -> {
                val sectionIndex = items.take(position + 1).count { it is SectionRow.Header } - 1
                (holder as HeaderViewHolder).bind(row.header, sectionIndex == selectedSectionIndex) {
                    onSectionClick(sectionIndex)
                }
            }
            is SectionRow.ItemRow -> {
                (holder as ItemViewHolder).bind(row.item)
            }
        }
    }

    private class HeaderViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(header: SectionHeader, isSelected: Boolean, onClick: () -> Unit) {
            textView.text = header.title
            val alpha = if (isSelected) 0xCC else 0x99
            textView.setBackgroundColor((alpha shl 24) or (header.color.toInt() and 0x00FFFFFF))
            textView.setOnClickListener { onClick() }
        }
    }

    private class ItemViewHolder(
        view: View,
        private val titleView: TextView,
        private val idView: TextView
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item) {
            titleView.text = item.title
            idView.text = "ID: ${item.id.take(8)}..."
        }
    }
}

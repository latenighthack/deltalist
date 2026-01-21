package com.latenighthack.deltalist.demo

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val FILTER_DIVISORS = listOf(2, 3, 5, 7, 11)

class PaginatedListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PaginatedListScreen()
                }
            }
        }
    }
}

@Composable
private fun PaginatedListScreen() {
    val viewModel = remember { PaginatedListViewModel() }
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
                0 -> PaginatedComposeContent(viewModel)
                1 -> PaginatedRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun PaginatedComposeContent(viewModel: PaginatedListViewModel) {
    val delta by viewModel.paginatedNumbers.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val loadingDirection by viewModel.paginatedLoadingDirection.collectAsState()
    val loadedCount by viewModel.paginatedLoadedCount.collectAsState()
    val excludeDivisors by viewModel.excludeDivisors.collectAsState()
    val isLoading = loadingDirection != null
    val estimatedSize = 10_000
    val filteredCount = delta.items.size

    Column(modifier = Modifier.fillMaxSize()) {
        PaginatedStatusBar(
            loadedSize = loadedCount,
            filteredSize = filteredCount,
            reportedSize = estimatedSize,
            isLoading = isLoading
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                count = filteredCount,
                key = { index ->
                    when (val soft = delta.items.softGetOrNull(index)) {
                        is SoftValue.Present -> soft.value
                        else -> index
                    }
                }
            ) { index ->
                when (val soft = delta.items.softGetOrNull(index)) {
                    is SoftValue.Present -> {
                        NumberItemCard(number = soft.value, index = index)
                    }
                    is SoftValue.NotLoaded -> {
                        try {
                            delta.items[index]
                        } catch (_: IndexOutOfBoundsException) {}
                        LoadingItemCard(index = index)
                    }
                    null -> {}
                }
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        DivisorFilterBar(
            excludeDivisors = excludeDivisors,
            onToggle = { viewModel.toggleDivisorFilter(it) }
        )
    }
}

@Composable
private fun PaginatedRecyclerViewContent(viewModel: PaginatedListViewModel) {
    val loadingDirection by viewModel.paginatedLoadingDirection.collectAsState()
    val loadedCount by viewModel.paginatedLoadedCount.collectAsState()
    val excludeDivisors by viewModel.excludeDivisors.collectAsState()
    val isLoading = loadingDirection != null
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        PaginatedStatusBar(
            loadedSize = loadedCount,
            filteredSize = 0,
            reportedSize = 10_000,
            isLoading = isLoading
        )

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = PaginatedNumberAdapter(viewModel.paginatedNumbers).also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                }
            )
        }

        DivisorFilterBar(
            excludeDivisors = excludeDivisors,
            onToggle = { viewModel.toggleDivisorFilter(it) }
        )
    }
}

@Composable
private fun DivisorFilterBar(
    excludeDivisors: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Exclude numbers divisible by:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FILTER_DIVISORS.forEach { divisor ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = divisor in excludeDivisors,
                        onCheckedChange = { onToggle(divisor) }
                    )
                    Text(text = "$divisor", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PaginatedStatusBar(
    loadedSize: Int,
    filteredSize: Int,
    reportedSize: Int,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Paginated List (10,000 items)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Loaded: $loadedSize / Filtered: $filteredSize / Total: $reportedSize",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NumberItemCard(number: Int, index: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$number",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "index: $index",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingItemCard(index: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp), strokeWidth = 2.dp)
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "index: $index",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// RecyclerView Adapter for paginated numbers
private class PaginatedNumberAdapter(
    private val deltaFlow: DeltaFlow<Int>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Int> = emptyList()
    private var softItems: SoftList<Int>? = null
    private var collectionJob: Job? = null

    companion object {
        private const val VIEW_TYPE_LOADED = 0
        private const val VIEW_TYPE_LOADING = 1
    }

    fun bind(owner: LifecycleOwner) {
        collectionJob?.cancel()
        collectionJob = owner.lifecycleScope.launch {
            deltaFlow.collect { delta -> applyDelta(delta) }
        }
    }

    private fun applyDelta(delta: Delta<Int>) {
        items = delta.items
        softItems = delta.items as? SoftList<Int>
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

    override fun getItemViewType(position: Int): Int {
        val soft = softItems?.softGet(position)
        return when (soft) {
            is SoftValue.Present -> VIEW_TYPE_LOADED
            is SoftValue.NotLoaded -> VIEW_TYPE_LOADING
            null -> VIEW_TYPE_LOADED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LOADING -> {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(48, 24, 48, 24)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val progress = ProgressBar(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(64, 64).apply {
                        marginEnd = 24
                    }
                }
                val text = TextView(parent.context).apply {
                    text = "Loading..."
                    textSize = 14f
                    setTextColor(0xFF888888.toInt())
                }
                layout.addView(progress)
                layout.addView(text)
                LoadingViewHolder(layout)
            }
            else -> {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(48, 24, 48, 24)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val numberView = TextView(parent.context).apply {
                    textSize = 24f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val indexView = TextView(parent.context).apply {
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                }
                layout.addView(numberView)
                layout.addView(indexView)
                NumberViewHolder(layout, numberView, indexView)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NumberViewHolder -> {
                val soft = softItems?.softGet(position)
                if (soft is SoftValue.Present) {
                    holder.bind(soft.value, position)
                }
            }
            is LoadingViewHolder -> {
                // Trigger fetch for unloaded items
                try {
                    items[position]
                } catch (_: IndexOutOfBoundsException) {}
            }
        }
    }

    private class NumberViewHolder(
        view: View,
        private val numberView: TextView,
        private val indexView: TextView
    ) : RecyclerView.ViewHolder(view) {
        fun bind(number: Int, index: Int) {
            numberView.text = "#$number"
            indexView.text = "index: $index"
        }
    }

    private class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

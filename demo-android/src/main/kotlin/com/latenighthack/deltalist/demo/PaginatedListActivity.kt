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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.android.compose.collectAsDeltaState
import com.latenighthack.deltalist.android.recyclerview.DeltaAdapter
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import com.latenighthack.deltalist.softGetOrNull

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
    val delta = viewModel.paginatedNumbers.collectAsDeltaState()
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
                    // Use distinct key prefixes to avoid collisions between
                    // loaded values (e.g., 8) and loading indices (e.g., 8)
                    when (val soft = delta.items.softGetOrNull(index)) {
                        is SoftValue.Present -> "v:${soft.value}"
                        else -> "i:$index"
                    }
                }
            ) { index ->
                when (val soft = delta.items.softGetOrNull(index)) {
                    is SoftValue.Present -> {
                        NumberItemCard(number = soft.value, index = index)
                    }
                    is SoftValue.NotLoaded -> {
                        // Trigger fetch by requesting the placeholder - the filter/pagination
                        // operators handle cascading automatically.
                        soft.request()
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
    // Driven by the adapter (the single collector of the delta flow) so it stays in sync with
    // the list as pages load and the filter recomputes.
    var filteredCount by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        PaginatedStatusBar(
            loadedSize = loadedCount,
            filteredSize = filteredCount,
            reportedSize = 10_000,
            isLoading = isLoading
        )

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = PaginatedNumberAdapter(viewModel.paginatedNumbers) { size ->
                            filteredCount = size
                        }.also { adapter ->
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
                text = "Paginated List",
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

// RecyclerView Adapter using DeltaAdapter with SoftList support
// Uses softGetItem() to determine view types for loaded vs loading states
private class PaginatedNumberAdapter(
    deltaList: DeltaList<Int>,
    private val onSizeChanged: (Int) -> Unit
) : DeltaAdapter<Int, RecyclerView.ViewHolder>(deltaList) {

    companion object {
        private const val VIEW_TYPE_LOADED = 0
        private const val VIEW_TYPE_LOADING = 1
    }

    override fun onItemsChanged() {
        onSizeChanged(itemCount)
    }

    override fun getItemViewType(position: Int): Int {
        return when (softGetItem(position)) {
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
                when (val soft = softGetItem(position)) {
                    is SoftValue.Present -> holder.bind(soft.value, position)
                    else -> {} // shouldn't happen if getItemViewType is correct
                }
            }
            is LoadingViewHolder -> {
                // Trigger the fetch by requesting the placeholder, mirroring the Compose path.
                // getItem() intentionally does NOT fetch — loads are explicit via request().
                // The filter/pagination operators handle cascading fetches automatically.
                (softGetItem(position) as? SoftValue.NotLoaded)?.request()
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

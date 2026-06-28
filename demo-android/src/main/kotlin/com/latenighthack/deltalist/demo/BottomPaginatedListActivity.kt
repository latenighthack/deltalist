package com.latenighthack.deltalist.demo

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
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

class BottomPaginatedListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BottomPaginatedListScreen()
                }
            }
        }
    }
}

@Composable
private fun BottomPaginatedListScreen() {
    val viewModel = remember { BottomPaginatedListViewModel() }
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
                0 -> BottomPaginatedComposeContent(viewModel)
                1 -> BottomPaginatedRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun BottomPaginatedComposeContent(viewModel: BottomPaginatedListViewModel) {
    val delta = viewModel.messages.collectAsDeltaState()
    val loadingDirection by viewModel.loadingDirection.collectAsState()
    val loadedCount by viewModel.loadedCount.collectAsState()
    val excludeDivisors by viewModel.excludeDivisors.collectAsState()
    val isLoading = loadingDirection != null
    val itemCount = delta.items.size

    val listState = rememberLazyListState()
    var didInitialScroll by remember { mutableStateOf(false) }
    var pendingScrollToBottom by remember { mutableStateOf(false) }

    // Anchor at the bottom as soon as the estimated size is known (skeleton rows show at the
    // bottom and fill in there). Runs once, so later prepends from scrolling up and filter
    // changes don't yank the viewport back down.
    LaunchedEffect(itemCount) {
        if (!didInitialScroll && itemCount > 1) {
            listState.scrollToItem(itemCount - 1)
            didInitialScroll = true
        }
    }

    // After an "add at bottom", scroll to reveal the appended item once it lands in the list.
    LaunchedEffect(itemCount) {
        if (pendingScrollToBottom && itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
            pendingScrollToBottom = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BottomPaginatedStatusBar(
            loadedSize = loadedCount,
            visibleSize = itemCount,
            isLoading = isLoading
        )

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(
                count = itemCount,
                key = { index ->
                    when (val soft = delta.items.softGetOrNull(index)) {
                        is SoftValue.Present -> "v:${soft.value}"
                        else -> "i:$index"
                    }
                }
            ) { index ->
                when (val soft = delta.items.softGetOrNull(index)) {
                    is SoftValue.Present -> NumberItemCard(number = soft.value, index = index)
                    is SoftValue.NotLoaded -> {
                        // Rendering a not-yet-loaded slot requests it, which drives the BEFORE
                        // fetch that prepends the previous (older) page.
                        soft.request()
                        PlaceholderItemCard()
                    }
                    null -> {}
                }
            }
        }

        AddButtonsBar(
            onAddTop = { viewModel.addAtTop() },
            onAddBottom = {
                viewModel.addAtBottom()
                pendingScrollToBottom = true
            }
        )

        DivisorFilterBar(
            excludeDivisors = excludeDivisors,
            onToggle = { viewModel.toggleDivisorFilter(it) }
        )
    }
}

@Composable
private fun BottomPaginatedRecyclerViewContent(viewModel: BottomPaginatedListViewModel) {
    val loadingDirection by viewModel.loadingDirection.collectAsState()
    val loadedCount by viewModel.loadedCount.collectAsState()
    val excludeDivisors by viewModel.excludeDivisors.collectAsState()
    val isLoading = loadingDirection != null
    val lifecycleOwner = LocalLifecycleOwner.current
    // Driven by the adapter (the single collector of the delta flow) so it stays in sync as pages
    // load, items are added, and the filter recomputes.
    var visibleCount by remember { mutableIntStateOf(0) }

    val adapter = remember {
        BottomPaginatedNumberAdapter(viewModel.messages) { size -> visibleCount = size }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BottomPaginatedStatusBar(
            loadedSize = loadedCount,
            visibleSize = visibleCount,
            isLoading = isLoading
        )

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        this.adapter = adapter.also { it.bind(lifecycleOwner) }
                    }
                }
            )
        }

        AddButtonsBar(
            onAddTop = { viewModel.addAtTop() },
            onAddBottom = {
                viewModel.addAtBottom()
                adapter.requestScrollToBottom()
            }
        )

        DivisorFilterBar(
            excludeDivisors = excludeDivisors,
            onToggle = { viewModel.toggleDivisorFilter(it) }
        )
    }
}

@Composable
private fun AddButtonsBar(
    onAddTop: () -> Unit,
    onAddBottom: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onAddTop, modifier = Modifier.weight(1f)) {
            Text("Add at top (0)")
        }
        Button(onClick = onAddBottom, modifier = Modifier.weight(1f)) {
            Text("Add at bottom (n)")
        }
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
private fun BottomPaginatedStatusBar(
    loadedSize: Int,
    visibleSize: Int,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Bottom Paginated List",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Loaded: $loadedSize / Visible rows: $visibleSize (scroll up for older)",
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
    // Manually-added items use negative values so they never collide with the paginated data.
    val isAdded = number < 0
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAdded) {
                Text(
                    text = "Added #${-number}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = "#$number",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "index: $index",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaceholderItemCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A skeleton bar standing in for the not-yet-loaded value — no spinner, no text.
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .fillMaxWidth(0.4f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

// RecyclerView adapter mirroring the Compose tab: loaded values render as number/added rows,
// not-yet-loaded slots render as skeleton rows whose request() drives the BEFORE (load-older) fetch.
private class BottomPaginatedNumberAdapter(
    deltaList: DeltaList<Int>,
    private val onSizeChanged: (Int) -> Unit
) : DeltaAdapter<Int, RecyclerView.ViewHolder>(deltaList) {

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_PLACEHOLDER = 1
    }

    private var recyclerView: RecyclerView? = null
    private var didInitialScroll = false
    private var scrollToBottomPending = false

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    /** Request a scroll to the bottom once the next delta (e.g. an appended item) lands. */
    fun requestScrollToBottom() {
        scrollToBottomPending = true
    }

    override fun onItemsChanged() {
        val count = itemCount
        onSizeChanged(count)
        val rv = recyclerView ?: return
        when {
            // Anchor at the bottom as soon as the estimated size is known (the bottom rows are
            // skeletons that then fill in there).
            !didInitialScroll && count > 1 -> {
                didInitialScroll = true
                rv.scrollToPosition(count - 1)
            }
            scrollToBottomPending && count > 0 -> {
                scrollToBottomPending = false
                rv.smoothScrollToPosition(count - 1)
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (softGetItem(position)) {
        is SoftValue.Present -> VIEW_TYPE_ITEM
        is SoftValue.NotLoaded -> VIEW_TYPE_PLACEHOLDER
        null -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PLACEHOLDER -> {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(48, 36, 48, 36)
                    gravity = Gravity.CENTER_VERTICAL
                }
                // A skeleton bar standing in for the not-yet-loaded value — no spinner, no text.
                val skeleton = View(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(320, 56)
                    setBackgroundColor(0xFFE0E0E0.toInt())
                }
                layout.addView(skeleton)
                PlaceholderViewHolder(layout)
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
            is NumberViewHolder -> when (val soft = softGetItem(position)) {
                is SoftValue.Present -> holder.bind(soft.value, position)
                else -> {}
            }
            is PlaceholderViewHolder -> {
                // Requesting the placeholder drives the BEFORE fetch (load older), mirroring Compose.
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
            // Manually-added items use negative values; render them distinctly.
            if (number < 0) {
                numberView.text = "Added #${-number}"
                numberView.setTextColor(0xFF6200EE.toInt())
                numberView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
            } else {
                numberView.text = "#$number"
                numberView.setTextColor(0xFF000000.toInt())
                numberView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
            indexView.text = "index: $index"
        }
    }

    private class PlaceholderViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

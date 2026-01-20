package com.latenighthack.deltalist.demo

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.softGetOrNull

private val FILTER_DIVISORS = listOf(2, 3, 5, 7, 11)

@Composable
fun PaginatedListScreen(viewModel: DemoViewModel) {
    // Collect to trigger recomposition on changes
    val delta by viewModel.paginatedNumbers.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val loadingDirection by viewModel.paginatedLoadingDirection.collectAsState()
    val loadedCount by viewModel.paginatedLoadedCount.collectAsState()
    val excludeDivisors by viewModel.excludeDivisors.collectAsState()
    val isLoading = loadingDirection != null
    val estimatedSize = 10_000 // We know the total size

    // The filtered list size (items that pass the filter)
    val filteredCount = delta.items.size

    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar showing loaded vs estimated size
        PaginatedStatusBar(
            loadedSize = loadedCount,
            filteredSize = filteredCount,
            reportedSize = estimatedSize,
            isLoading = isLoading
        )

        // Filter checkboxes
        DivisorFilterBar(
            excludeDivisors = excludeDivisors,
            onToggle = { viewModel.toggleDivisorFilter(it) }
        )

        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Use filteredCount for item count (filtered list size)
            // Access through delta.items to trigger fetches near boundaries (wrapper handles this)
            items(
                count = filteredCount,
                key = { index ->
                    // Use softGetOrNull to avoid triggering fetches when computing keys
                    when (val soft = delta.items.softGetOrNull(index)) {
                        is SoftValue.Present -> soft.value
                        else -> index
                    }
                }
            ) { index ->
                // Use softGetOrNull to check if item is loaded
                // get() will trigger fetches for unloaded items but throws
                when (val soft = delta.items.softGetOrNull(index)) {
                    is SoftValue.Present -> {
                        NumberItemCard(
                            number = soft.value,
                            index = index,
                            isLoaded = true
                        )
                    }
                    is SoftValue.NotLoaded -> {
                        // Item not yet loaded - show placeholder and trigger fetch
                        try {
                            delta.items[index] // Trigger fetch (will throw)
                        } catch (_: IndexOutOfBoundsException) {
                            // Expected - fetch was triggered
                        }
                        LoadingItemCard(index = index)
                    }
                    null -> {
                        // Out of bounds - shouldn't happen
                    }
                }
            }

            // Loading indicator at the bottom when fetching
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = divisor in excludeDivisors,
                        onCheckedChange = { onToggle(divisor) }
                    )
                    Text(
                        text = "$divisor",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Paginated List Demo (10,000 items)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Loaded: $loadedSize / Filtered: $filteredSize / Total: $reportedSize",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun NumberItemCard(
    number: Int,
    index: Int,
    isLoaded: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 16.dp),
                strokeWidth = 2.dp
            )
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

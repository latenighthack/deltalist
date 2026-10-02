package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.acquireOrGet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Collects a flow from an item with automatic lifecycle management.
 *
 * Flow collection starts when the composable enters composition and stops on disposal.
 * This is useful for items that expose observable state via [Flow].
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun TickingItemCard(tickingItem: TickingItem) {
 *     val tickCount = rememberItemState(
 *         item = tickingItem,
 *         key = tickingItem.id,
 *         initialValue = 0
 *     ) { it.tickCount }
 *
 *     Card {
 *         Text("Ticks: $tickCount")
 *     }
 * }
 *
 * // In LazyColumn:
 * LazyColumn {
 *     items(delta.items, key = { it.id }) { item ->
 *         TickingItemCard(item)  // Each card manages its own flow
 *     }
 * }
 * ```
 *
 * @param item The item containing the flow
 * @param key Key for recomposition identity (use item.id or similar stable identifier)
 * @param initialValue Initial state before first emission
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, updated as the flow emits
 */
@Composable
fun <T, S> rememberItemState(
    item: T,
    key: Any,
    initialValue: S,
    flowAccessor: (T) -> Flow<S>
): S {
    var state by remember(key) { mutableStateOf(initialValue) }

    LaunchedEffect(key, item) {
        flowAccessor(item).collectLatest { state = it }
    }

    return state
}

/**
 * Variant of [rememberItemState] that returns nullable state.
 *
 * Returns null until the first emission from the flow. Useful when there's no
 * sensible default value.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun UserProfileCard(user: User) {
 *     val profile = rememberItemStateOrNull(
 *         item = user,
 *         key = user.id
 *     ) { it.profileFlow }
 *
 *     if (profile != null) {
 *         Text("Name: ${profile.name}")
 *     } else {
 *         CircularProgressIndicator()
 *     }
 * }
 * ```
 *
 * @param item The item containing the flow
 * @param key Key for recomposition identity
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, or null before first emission
 */
@Composable
fun <T, S : Any> rememberItemStateOrNull(
    item: T,
    key: Any,
    flowAccessor: (T) -> Flow<S>
): S? = rememberItemState(item, key, null, flowAccessor)

/**
 * Collects a flow from a lazy item with automatic lazy lifecycle management.
 *
 * When the list is a [LazyList] (e.g., from [lazyMap().withStableIds()]):
 * - The item is accessed via index (auto-acquired)
 * - The item is released when the composable leaves composition
 * - The flow is collected while in composition
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun LazyTickingCard(
 *     items: List<StableItem<TickingItem>>,
 *     index: Int,
 *     stableId: Int
 * ) {
 *     val tickCount = items.rememberLazyItemState(
 *         index = index,
 *         key = stableId,
 *         initialValue = 0
 *     ) { stableItem -> stableItem.value.tickCount }
 *
 *     Card {
 *         Text("Ticks: $tickCount | StableId: $stableId")
 *     }
 * }
 * ```
 *
 * @param index The index of the item in the list
 * @param key Key for recomposition identity
 * @param initialValue Initial state before first emission
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, updated as the flow emits
 */
@Composable
fun <T, S> SoftList<T>.rememberLazyItemState(
    index: Int,
    key: Any,
    initialValue: S,
    flowAccessor: (T) -> Flow<S>
): S {
    val item = rememberItem(index, key)

    return rememberItemState(item, key, initialValue, flowAccessor)
}

/**
 * Nullable variant of [List.rememberLazyItemState].
 *
 * @param index The index of the item in the list
 * @param key Key for recomposition identity
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, or null before first emission
 */
@Composable
fun <T, S : Any> SoftList<T>.rememberLazyItemStateOrNull(
    index: Int,
    key: Any,
    flowAccessor: (T) -> Flow<S>
): S? = rememberLazyItemState(index, key, null, flowAccessor)

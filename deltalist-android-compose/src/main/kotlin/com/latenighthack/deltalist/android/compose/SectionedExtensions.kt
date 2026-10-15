package com.latenighthack.deltalist.android.compose

import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedDeltaList
import com.latenighthack.deltalist.SectionedIds
import com.latenighthack.deltalist.SectionedStableIds
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.flow

/**
 * One emission of a [SectionedDeltaList], with keys for every header and item.
 *
 * @property sections The current sections, in order.
 * @property change How the sections changed from the previous emission.
 */
class SectionedDeltaState<H, T> internal constructor(
    val sections: List<SectionState<H, T>>,
    val change: SectionedChange,
)

/**
 * A section of a [SectionedDeltaState].
 *
 * [key] and [itemKey] are unique across the whole state (headers and items alike) and can be
 * passed directly as `LazyColumn` keys, so a header or row keeps its key while it is moved.
 */
class SectionState<H, T> internal constructor(
    val header: H,
    val items: SoftList<T>,
    private val sectionIndex: Int,
    private val ids: SectionedIds,
    headerKeyOf: ((H) -> Any)?,
    private val itemKeyOf: ((T) -> Any)?,
) {
    /** The number of items, including unloaded positions. */
    val size: Int get() = items.size

    /** Key for this section's header. */
    val key: Any = headerKeyOf?.let { SectionedRowKey(HEADER, it(header)) }
        ?: SectionedRowKey(TRACKED_HEADER, ids.sectionIds[sectionIndex])

    /** Key for the item at [index]. Unloaded positions use a tracked key. */
    fun itemKey(index: Int): Any {
        val keyOf = itemKeyOf
        if (keyOf != null) {
            val value = items.softGet(index)
            if (value is SoftValue.Present) return SectionedRowKey(ITEM, keyOf(value.value))
        }
        return SectionedRowKey(TRACKED_ITEM, ids.itemId(sectionIndex, index))
    }
}

/**
 * Collects a [SectionedDeltaList] as Compose state ready for `LazyColumn`.
 *
 * Keys are tracked through section and item mutations, so moves, insertions and removals
 * animate instead of reloading. On a reload, a section keeps its key when an equal header
 * was present before, while item keys are regenerated. Pass [itemKey] (and [headerKey] if
 * headers are not comparable by equality) to key rows by domain identity that survives
 * reloads; returned keys must be unique and, as with any `LazyColumn` key, Bundle-saveable.
 *
 * The host chooses how each header is shown:
 * ```kotlin
 * val state = catalog.collectAsSectionedDeltaState(itemKey = { it.id })
 * LazyColumn {
 *     for (section in state.sections) {
 *         stickyHeader(key = section.key) { CatalogHeader(section.header) }
 *         sectionItems(section, placeholder = { AppPlaceholder() }) { _, app -> AppRow(app) }
 *     }
 * }
 * ```
 *
 * Item lifecycle matches [rememberItem]: rows rendered through [sectionItems] lease lazy
 * items while composed and release them when they leave composition.
 *
 * @param headerKey Optional domain key for headers.
 * @param itemKey Optional domain key for loaded items.
 */
@Composable
fun <H, T> SectionedDeltaList<H, T>.collectAsSectionedDeltaState(
    headerKey: ((H) -> Any)? = null,
    itemKey: ((T) -> Any)? = null,
): SectionedDeltaState<H, T> {
    val source = this
    val keyed = remember(source) {
        flow {
            val tracker = SectionedStableIds()
            source.collect { delta -> emit(KeyedSectionedDelta(delta, tracker.apply(delta))) }
        }
    }
    val emission by keyed.collectAsState(initial = null)
    return remember(emission, headerKey, itemKey) {
        val current = emission ?: return@remember SectionedDeltaState(emptyList(), SectionedChange.Reload)
        SectionedDeltaState(
            sections = current.delta.sections.mapIndexed { index, section ->
                SectionState(section.header, section.items, index, current.ids, headerKey, itemKey)
            },
            change = current.delta.change,
        )
    }
}

/**
 * Emits one `LazyColumn` row per position of [section], keyed by [SectionState.itemKey].
 *
 * Loaded items are read through [rememberItem], so lazy items are leased while their row is
 * composed. Unloaded positions render [placeholder] and request their page once per snapshot.
 */
fun <H, T> LazyListScope.sectionItems(
    section: SectionState<H, T>,
    contentType: (index: Int) -> Any? = { null },
    placeholder: @Composable LazyItemScope.(index: Int) -> Unit = {},
    itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
) {
    val items = section.items
    items(count = section.size, key = section::itemKey, contentType = contentType) { index ->
        when (val value = items.softGet(index)) {
            is SoftValue.Present -> itemContent(index, items.rememberItem(index, section.itemKey(index)))
            is SoftValue.NotLoaded -> {
                LaunchedEffect(SnapshotIdentity(items), index) { value.request() }
                placeholder(index)
            }
            null -> Unit
        }
    }
}

private class KeyedSectionedDelta<H, T>(val delta: SectionedDelta<H, T>, val ids: SectionedIds)

private const val TRACKED_HEADER = 0
private const val HEADER = 1
private const val TRACKED_ITEM = 2
private const val ITEM = 3

// Lazy layouts require Bundle-saveable keys. The kind keeps header, item, tracked and domain
// keys apart; a domain value must itself be Parcel-writable when the layout saves state.
internal data class SectionedRowKey(val kind: Int, val value: Any?) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(kind)
        dest.writeValue(value)
    }

    companion object CREATOR : Parcelable.Creator<SectionedRowKey> {
        override fun createFromParcel(source: Parcel): SectionedRowKey =
            SectionedRowKey(source.readInt(), source.readValue(SectionedRowKey::class.java.classLoader))

        override fun newArray(size: Int): Array<SectionedRowKey?> = arrayOfNulls(size)
    }
}

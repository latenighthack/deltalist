package com.latenighthack.deltalist

/**
 * Represents a value that may or may not be loaded yet.
 */
sealed class SoftValue<out T> {
    /**
     * The value is present and loaded.
     */
    data class Present<T>(val value: T) : SoftValue<T>()

    /**
     * The value is within the expected bounds but not yet loaded.
     * This typically occurs with paginated lists where the estimated size
     * is larger than the currently loaded items.
     *
     * Call [request] to trigger a fetch for this value if one is available.
     */
    class NotLoaded(private val onRequest: (() -> Unit)? = null) : SoftValue<Nothing>() {
        /**
         * Requests that the value be loaded. This will trigger the appropriate
         * fetch operation if one is available. Has no effect if no fetch
         * operation is associated with this NotLoaded instance.
         */
        fun request() {
            onRequest?.invoke()
        }

        // All NotLoaded instances are equal regardless of callback
        override fun equals(other: Any?): Boolean = other is NotLoaded
        override fun hashCode(): Int = NotLoaded::class.hashCode()
        override fun toString(): String = "NotLoaded"
    }
}

/**
 * The unified snapshot type carried by [Delta]. Unlike a [List], a `SoftList` is honest
 * about *load state*: a position may hold a [SoftValue.Present] value or be
 * [SoftValue.NotLoaded] (a placeholder a paginated/lazy source has not fetched yet).
 *
 * It deliberately does **not** extend [List]: there is no `get(i): T` (which would have to
 * throw or lie for unloaded slots), no `iterator`/`toList`/`subList`/`==` that would
 * silently iterate (and for soft sources trigger fetches). Read with [softGet]; to obtain
 * an ordinary list of the loaded values, call [softLoadedItems].
 *
 * ## Contract
 * A `SoftList` is an **immutable value for reading** — [softGet] is deterministic for the
 * life of the instance and never mutates anything. Some implementations additionally
 * expose side-effecting surfaces ([SoftValue.NotLoaded.request], and [LazyList]'s
 * lifecycle) which are honored **only while the snapshot is the current one**; a held,
 * superseded snapshot's side-effecting calls become safe no-ops.
 */
interface SoftList<out T> {
    /** Total number of positions, including not-yet-loaded ones. */
    val size: Int

    /**
     * Gets the value at the index without triggering any side effects.
     *
     * @return `null` if the index is out of bounds (negative or >= [size]),
     *         [SoftValue.NotLoaded] if the index is within bounds but not yet loaded,
     *         or [SoftValue.Present] containing the value if it is loaded.
     */
    fun softGet(index: Int): SoftValue<T>?
}

/**
 * Base class giving every [SoftList] honest structural equality: two soft lists are equal
 * when they have the same [size] and the same per-position load state and loaded values.
 * This replaces the old non-structural `equals` overrides (which existed only to dodge
 * `List` iteration) so [Delta] conflation in StateFlow/Compose behaves correctly.
 */
abstract class AbstractSoftList<out T> : SoftList<T> {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftList<*>) return false
        if (size != other.size) return false
        for (i in 0 until size) {
            val a = softGet(i)
            val b = other.softGet(i)
            when {
                a is SoftValue.Present && b is SoftValue.Present -> if (a.value != b.value) return false
                a is SoftValue.NotLoaded && b is SoftValue.NotLoaded -> {}
                else -> return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = size
        for (i in 0 until size) {
            val v = softGet(i)
            result = 31 * result + if (v is SoftValue.Present) v.value.hashCode() else 0
        }
        return result
    }

    override fun toString(): String = "SoftList(size=$size, loaded=${softLoadedItems()})"
}

/**
 * A fully-loaded [SoftList] backed by an ordinary [List] — every slot is
 * [SoftValue.Present]. This is the in-memory on-ramp: lifting a plain list into the
 * soft world costs nothing and never surfaces [SoftValue.NotLoaded].
 */
internal class FullSoftList<T>(private val backing: List<T>) : AbstractSoftList<T>() {
    internal fun snapshot(): SoftList<T> = FullSoftList(backing.toList())
    override val size: Int get() = backing.size
    override fun softGet(index: Int): SoftValue<T>? =
        if (index in backing.indices) SoftValue.Present(backing[index]) else null
}

/** Lifts an ordinary, fully-loaded [List] into a [SoftList] with no per-item ceremony. */
fun <T> List<T>.asSoftList(): SoftList<T> = FullSoftList(this)

/** Alias for [SoftList.softGet]; retained for call-site compatibility. */
fun <T> SoftList<T>.softGetOrNull(index: Int): SoftValue<T>? = softGet(index)

/** Total number of positions (including unloaded placeholders). */
fun <T> SoftList<T>.softSize(): Int = size

/** The number of loaded ([SoftValue.Present]) items (the loaded prefix). */
fun <T> SoftList<T>.softLoadedCount(): Int {
    var count = 0
    for (i in 0 until size) {
        when (softGet(i)) {
            is SoftValue.Present -> count++
            is SoftValue.NotLoaded -> break // Loaded items are contiguous at the start
            null -> break
        }
    }
    return count
}

/** Maps the contiguous loaded prefix, stopping at the first gap without requesting it. */
fun <T, R> SoftList<T>.softMapLoaded(transform: (T) -> R): List<R> {
    val result = mutableListOf<R>()
    for (i in 0 until size) {
        when (val soft = softGet(i)) {
            is SoftValue.Present -> result.add(transform(soft.value))
            is SoftValue.NotLoaded -> break
            null -> break
        }
    }
    return result
}

/** Iterates the contiguous loaded prefix, stopping at the first gap without requesting it. */
inline fun <T> SoftList<T>.softForEachLoaded(action: (T) -> Unit) {
    for (i in 0 until size) {
        when (val soft = softGet(i)) {
            is SoftValue.Present -> action(soft.value)
            is SoftValue.NotLoaded -> break
            null -> break
        }
    }
}

/**
 * Returns an ordinary [List] of the loaded values — the explicit materialize escape hatch.
 * It may be a partial view (only the loaded prefix) for a soft/paginated source.
 */
fun <T> SoftList<T>.softLoadedItems(): List<T> {
    val result = mutableListOf<T>()
    for (i in 0 until size) {
        when (val soft = softGet(i)) {
            is SoftValue.Present -> result.add(soft.value)
            is SoftValue.NotLoaded -> break
            null -> break
        }
    }
    return result
}

// ============================================================================
// Delta helpers - these take Delta directly to avoid iOS bridging issues
// when accessing delta.items from Swift
// ============================================================================

/**
 * Returns a list containing only the loaded items from a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.loadedItems(): List<T> = items.softLoadedItems()

/**
 * Returns the count of loaded items in a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.loadedCount(): Int = items.softLoadedCount()

/**
 * Maps over only the loaded items in a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T, R> Delta<T>.mapLoaded(transform: (T) -> R): List<R> = items.softMapLoaded(transform)

/**
 * Returns the total size of items in a Delta (including estimated unloaded items for paginated lists).
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.totalSize(): Int = items.size

/**
 * Requests loading of more items if there are unloaded items available.
 * Call this when the UI scrolls near the end of the loaded items to trigger pagination.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 *
 * @param index The index to request loading for (typically loadedCount or near the end of visible items)
 */
fun <T> Delta<T>.requestLoadAt(index: Int) {
    val softValue = items.softGetOrNull(index)
    if (softValue is SoftValue.NotLoaded) {
        softValue.request()
    }
}

/**
 * Checks if an item at the given index is loaded.
 * Returns true if the item is present, false if not loaded or out of bounds.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.isLoadedAt(index: Int): Boolean {
    return items.softGetOrNull(index) is SoftValue.Present
}

/**
 * Gets an item at the given index if it's loaded, or null if not loaded/out of bounds.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.getLoadedItemAt(index: Int): T? {
    return (items.softGetOrNull(index) as? SoftValue.Present)?.value
}

/**
 * Triggers a fetch for an item at the given index.
 * This should be called when displaying a loading placeholder to trigger pagination.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.triggerLoadAt(index: Int) {
    // Fetching is now triggered explicitly via the placeholder's request() — no more
    // throw-to-fetch hard access.
    val softValue = items.softGetOrNull(index)
    if (softValue is SoftValue.NotLoaded) {
        softValue.request()
    }
}

// ============================================================================
// SectionedDelta helpers - these avoid iOS bridging issues
// when accessing delta.sections from Swift
// ============================================================================

/**
 * Returns the number of sections in a SectionedDelta.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.sectionCount(): Int = sections.size

/**
 * Returns the section at the given index, or null if out of bounds.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.getSectionAt(index: Int): Section<S, T>? =
    sections.getOrNull(index)

/**
 * Returns the header at the given section index, or null if out of bounds.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.getHeaderAt(sectionIndex: Int): S? =
    sections.getOrNull(sectionIndex)?.header

/**
 * Returns the number of items in the section at the given index, or 0 if out of bounds.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.getItemCountAt(sectionIndex: Int): Int =
    sections.getOrNull(sectionIndex)?.items?.size ?: 0

/**
 * Returns the item at the given section and item indices, or null if out of bounds.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.getItemAt(sectionIndex: Int, itemIndex: Int): T? =
    (sections.getOrNull(sectionIndex)?.items?.softGet(itemIndex) as? SoftValue.Present)?.value

/**
 * Returns the loaded items in the section at the given index.
 * This is safe to call from iOS - pass the SectionedDelta directly to avoid bridging issues.
 */
fun <S, T> SectionedDelta<S, T>.getItemsAt(sectionIndex: Int): List<T> =
    sections.getOrNull(sectionIndex)?.items?.softLoadedItems() ?: emptyList()

/**
 * Returns the loaded items of a [Section] as an ordinary list. Section items are soft, so
 * this is the safe way to read them from Swift (avoids bridging the soft wrapper).
 */
fun <S, T> Section<S, T>.loadedItems(): List<T> = items.softLoadedItems()

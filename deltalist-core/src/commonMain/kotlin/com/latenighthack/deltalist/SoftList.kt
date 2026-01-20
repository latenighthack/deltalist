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
 * A list that supports "soft" access to elements without triggering side effects.
 *
 * Regular [get] access may trigger side effects like pagination fetches when
 * accessing items near boundaries. [softGet] allows inspecting whether a value
 * exists without triggering these side effects.
 *
 * This is useful for operators like filter or map that need to iterate over
 * items without inadvertently triggering fetches for unloaded data.
 */
interface SoftList<out T> : List<T> {
    /**
     * Gets the value at the index without triggering any side effects.
     *
     * @param index The index to access
     * @return `null` if the index is out of bounds (negative or >= size),
     *         [SoftValue.NotLoaded] if the index is within bounds but the value
     *         is not yet loaded, or [SoftValue.Present] containing the value
     *         if it is loaded.
     */
    fun softGet(index: Int): SoftValue<T>?
}

/**
 * Extension to safely get a value from any list, returning a [SoftValue].
 * For regular lists, this will return [SoftValue.Present] or null.
 * For [SoftList] implementations, this delegates to [SoftList.softGet].
 */
fun <T> List<T>.softGetOrNull(index: Int): SoftValue<T>? {
    return if (this is SoftList<T>) {
        softGet(index)
    } else {
        if (index < 0 || index >= size) null
        else SoftValue.Present(get(index))
    }
}

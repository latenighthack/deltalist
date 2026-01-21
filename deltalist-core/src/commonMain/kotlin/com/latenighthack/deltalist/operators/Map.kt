package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.map

/**
 * Lazy list that transforms items on access.
 * Implements [SoftList] to propagate soft access from the source.
 */
internal class MappedList<T, R>(
    private val source: List<T>,
    private val transform: (T) -> R
) : AbstractList<R>(), SoftList<R> {
    override val size: Int get() = source.size
    override fun get(index: Int): R = transform(source[index])

    override fun softGet(index: Int): SoftValue<R>? {
        return if (source is SoftList<T>) {
            when (val soft = source.softGet(index)) {
                is SoftValue.Present -> SoftValue.Present(transform(soft.value))
                is SoftValue.NotLoaded -> soft
                null -> null
            }
        } else {
            if (index < 0 || index >= source.size) null
            else SoftValue.Present(transform(source[index]))
        }
    }
}

fun <T, R> DeltaList<T>.mapItems(transform: (T) -> R): DeltaList<R> = map { delta ->
    Delta(
        items = MappedList(delta.items, transform),
        change = delta.change
    )
}

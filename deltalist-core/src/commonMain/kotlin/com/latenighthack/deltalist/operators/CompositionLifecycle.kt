package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A collection owns this generation; unchanged child snapshots must not revive old wrappers. */
@OptIn(ExperimentalAtomicApi::class)
internal class CompositionLifetime {
    private val generation = AtomicReference(Any())
    fun next(): () -> Boolean {
        val token = Any()
        generation.store(token)
        return { generation.load() === token }
    }
    fun close() { generation.store(Any()) }
}

internal interface LifecycleRoute<T> {
    fun acquire(): SoftValue<T>
    fun lease(): ItemLease<T>?
    fun release()
    fun isAcquired(): Boolean
}

internal fun <S, T> route(source: SoftList<S>, index: Int, transform: (S) -> T): LifecycleRoute<T> =
    object : LifecycleRoute<T> {
        override fun acquire(): SoftValue<T> = when (val value = source.acquireOrGet(index)) {
            is SoftValue.Present -> try { SoftValue.Present(transform(value.value)) }
                catch (error: Throwable) { source.releaseIfLazy(index); throw error }
            is SoftValue.NotLoaded -> value
        }
        override fun lease(): ItemLease<T>? = source.acquireItemOrGet(index)?.mapItem(transform)
        override fun release() = source.releaseIfLazy(index)
        override fun isAcquired(): Boolean = (source as? LazyList<*>)?.isAcquired(index) ?: false
    }

internal fun <T> concatenatedRoute(lists: List<SoftList<T>>, index: Int): LifecycleRoute<T>? {
    if (index < 0) return null
    var remaining = index
    for (list in lists) {
        if (remaining < list.size) return route(list, remaining) { it }
        remaining -= list.size
    }
    return null
}

internal fun <T> lifecycleList(
    backing: SoftList<T>,
    sources: List<SoftList<*>>,
    isCurrent: () -> Boolean,
    locate: (Int) -> LifecycleRoute<T>?
): SoftList<T> {
    val guarded = object : AbstractSoftList<T>() {
        override val size: Int get() = backing.size
        override fun softGet(index: Int): SoftValue<T>? = when (val value = backing.softGet(index)) {
            is SoftValue.NotLoaded -> SoftValue.NotLoaded { if (isCurrent()) value.request() }
            else -> value
        }
    }
    if (sources.none { it is LazyList<*> }) return guarded
    return object : AbstractSoftList<T>(), LeasedLazyList<T> {
        override val size: Int get() = backing.size
        override fun softGet(index: Int): SoftValue<T>? = guarded.softGet(index)
        override fun acquire(index: Int): SoftValue<T> {
            if (!isCurrent()) return softGet(index) ?: SoftValue.NotLoaded()
            val value = locate(index)?.acquire() ?: softGet(index) ?: SoftValue.NotLoaded()
            return if (value is SoftValue.NotLoaded) SoftValue.NotLoaded { if (isCurrent()) value.request() } else value
        }
        override fun acquireItem(index: Int): ItemLease<T>? {
            if (!isCurrent() || index !in 0 until size) return null
            val route = locate(index)
            return if (route != null) route.lease()
                else (softGet(index) as? SoftValue.Present)?.let { ItemLease(it.value) {} }
        }
        override fun release(index: Int) { if (isCurrent()) locate(index)?.release() }
        override fun isAcquired(index: Int): Boolean = isCurrent() && locate(index)?.isAcquired() == true
        override fun releaseAll() {
            if (!isCurrent()) return
            val seen = mutableListOf<SoftList<*>>()
            for (source in sources) if (seen.none { it === source }) {
                seen.add(source)
                (source as? LazyList<*>)?.releaseAll()
            }
        }
    }
}

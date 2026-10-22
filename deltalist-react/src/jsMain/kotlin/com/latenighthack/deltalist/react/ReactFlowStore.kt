package com.latenighthack.deltalist.react

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A [Flow] as a React external store. Pass [subscribe] and [getSnapshot] to
 * `React.useSyncExternalStore`; both keep their identity for the store's lifetime.
 *
 * - The first [getSnapshot] starts collecting [source] on [Dispatchers.Unconfined], undispatched.
 *   A source that emits on subscription, such as a `StateFlow` or a `combine` of them, has
 *   emitted before [getSnapshot] returns, so the first render shows that value. Otherwise the
 *   snapshot is [initial] until the source emits.
 * - One collection serves every subscriber. When the last subscriber leaves, the collection stops
 *   after [graceMillis] unless another subscribes first. A render React discards never subscribes,
 *   so the same timer stops a collection it started. StrictMode's unmount and remount reuse it.
 * - A subscriber that arrives after the collection stopped starts it again.
 * - An emission equal to the current snapshot is dropped, so React does not re-render for it.
 *   Otherwise the snapshot keeps its identity until the next emission. [onStop] runs each time the
 *   collection stops; the snapshot is kept.
 */
public class ReactFlowStore<T>(
    initial: T,
    private val source: Flow<T>,
    private val graceMillis: Int = defaultGraceMillis,
    private val onStop: () -> Unit = {},
) {
    private class Listener(val notify: () -> Unit)

    private var current: T = initial
    private val listeners = mutableListOf<Listener>()
    private var scope: CoroutineScope? = null
    private var timer: dynamic = null
    private var starting = false

    /** Whether [source] is being collected. */
    public val isCollecting: Boolean get() = scope != null

    /** The current snapshot. Starts the collection if it is stopped. */
    public val getSnapshot: () -> T = {
        if (scope == null) {
            start()
            if (listeners.isEmpty()) armTimer()
        }
        current
    }

    /** Adds [listener]; the returned function removes it. */
    public val subscribe: (listener: () -> Unit) -> () -> Unit = { listener ->
        val entry = Listener(listener)
        listeners.add(entry)
        cancelTimer()
        if (scope == null) start()
        val unsubscribe: () -> Unit = {
            if (listeners.remove(entry) && listeners.isEmpty()) armTimer()
        }
        unsubscribe
    }

    /** Stops the collection now. A later [getSnapshot] or [subscribe] starts it again. */
    public fun stop() {
        cancelTimer()
        val running = scope ?: return
        scope = null
        running.cancel()
        onStop()
    }

    private fun start() {
        val next = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope = next
        // Emissions during the start are read through the snapshot; React re-reads it after
        // subscribing, and notifying during a render would update another component mid-render.
        starting = true
        try {
            next.launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect { value -> if (scope === next) publish(value) }
            }
        } finally {
            starting = false
        }
    }

    private fun publish(value: T) {
        if (value == current) return
        current = value
        if (!starting) listeners.toList().forEach { it.notify() }
    }

    private fun armTimer() {
        cancelTimer()
        timer = setTimeout({
            timer = null
            if (listeners.isEmpty()) stop()
        }, graceMillis)
    }

    private fun cancelTimer() {
        val pending = timer ?: return
        timer = null
        clearTimeout(pending)
    }

    public companion object {
        /**
         * How long a collection outlives its last subscriber, in milliseconds. It must cover the
         * time between a render and React's passive effects, where `useSyncExternalStore`
         * subscribes; shorter values restart the collection for every mount.
         */
        public var defaultGraceMillis: Int = 1000
    }
}

private external fun setTimeout(handler: () -> Unit, timeout: Int): dynamic
private external fun clearTimeout(handle: dynamic)

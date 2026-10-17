package com.latenighthack.deltalist.android.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.StableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Binds this [DeltaList] of [StableItem]-wrapped items to system-tray notifications.
 *
 * Use this overload when ids are synthesized by `.withStableIds()`; the notification payload
 * is each item's [StableItem.value]. The returned notifier is unbound; call
 * [DeltaNotifier.bind] to start syncing the tray.
 *
 * ```kotlin
 * val notifier = downloads.withStableIds().notifier(context) {
 *     channel("downloads", "Downloads")
 *     content { dl -> notification { setSmallIcon(R.drawable.ic); setContentTitle(dl.name) } }
 *     onAction { dl, key -> /* ... */ }
 * }.bind(lifecycleOwner)
 * ```
 */
fun <T> DeltaList<StableItem<T>>.notifier(
    context: Context,
    build: NotifierBuilder<StableItem<T>, T>.() -> Unit,
): DeltaNotifier<StableItem<T>, T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<StableItem<T>, T>().apply(build)
    return DeltaNotifier(
        appContext, this, builder.toConfig(TrayIdentity.StableIds), builder.toOptions(), builder.toSummary(),
        keyOf = { it.stableId.toString() },
        valueOf = { it.value },
    )
}

/**
 * Binds this [DeltaList] of self-identifying items to system-tray notifications.
 *
 * Use this when the item type already implements [Stable]; the item itself is the
 * notification payload, so no `.withStableIds()` wrapping is needed. (It has a distinct name
 * from [notifier] only because, under an invariant `Delta`, the two cannot be disambiguated
 * by overload resolution for `StableItem` inputs.)
 *
 * ```kotlin
 * data class Download(override val stableId: Int, val name: String) : Stable
 * val notifier = downloads.notifierForStable(context) {
 *     channel("downloads", "Downloads")
 *     content { dl -> notification { setContentTitle(dl.name) } }
 * }.bind(lifecycleOwner)
 * ```
 */
fun <T : Stable> DeltaList<T>.notifierForStable(
    context: Context,
    build: NotifierBuilder<T, T>.() -> Unit,
): DeltaNotifier<T, T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<T, T>().apply(build)
    return DeltaNotifier(
        appContext, this, builder.toConfig(TrayIdentity.StableIds), builder.toOptions(), builder.toSummary(),
        keyOf = { it.stableId.toString() },
        valueOf = { it },
    )
}

/**
 * Binds this [DeltaList] to system-tray notifications under a durable [key] per item, such as
 * a server-issued notification id.
 *
 * Each item's notification is posted as `notify(tag = key, id = idBase)`, so its address
 * survives reloads and process restarts, and matches a notification posted for that key
 * elsewhere (an earlier process, or the app's own push handling). With [NotifierBuilder.adoptDelivered],
 * an item whose notification the tray already shows is adopted rather than posted again.
 * The `idSpace` tag only routes this notifier's interactions.
 *
 * ```kotlin
 * val notifier = requests.notifier(context, key = { it.notificationId }) {
 *     channel("requests", "Requests")
 *     adoptDelivered()
 *     itemState(accessor = { it.isShown })
 *     content { request -> if (state == false) null else notification { setContentTitle(request.title) } }
 * }.bind(scope)
 * ```
 */
fun <T> DeltaList<T>.notifier(
    context: Context,
    key: (T) -> String,
    build: NotifierBuilder<T, T>.() -> Unit,
): DeltaNotifier<T, T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<T, T>().apply(build)
    return DeltaNotifier(
        appContext, this, builder.toConfig(TrayIdentity.Keys), builder.toOptions(), builder.toSummary(),
        keyOf = key,
        valueOf = { it },
    )
}

/**
 * DSL for configuring a [DeltaNotifier].
 *
 * @param E the list element type (a [StableItem] wrapper, a self-identifying [Stable] type, or
 *   an item with its own key).
 * @param T the notification payload type fed to [content] and the interaction handlers.
 */
class NotifierBuilder<E, T> internal constructor() {
    private val channels = mutableListOf<ChannelSpec>()
    private var tag: String = "deltalist"
    private var idBase: Int = 0
    private var groupKey: String? = null
    private var groupSummary: (NotificationSummaryScope.(List<E>) -> Notification)? = null
    private var content: (NotificationScope<T>.(T) -> Notification?)? = null
    private var shouldRepost: (T, T) -> Boolean = { old, new -> old !== new }
    private var adoptDelivered: ((T, StatusBarNotification) -> Boolean)? = null
    private var stateAccessor: ((T) -> Flow<Any?>)? = null
    private var stateInitial: ((T) -> Any?)? = null
    private var onDismiss: ((T) -> Unit)? = null
    private var onContentTap: ((T) -> Unit)? = null
    private var onAction: ((T, String) -> Unit)? = null

    /** If false (default), [DeltaNotifier.unbind] leaves posted notifications standing. */
    var cancelOnUnbind: Boolean = false

    /** Upper bound on `itemState` re-posts per item per second (under Android's ~10/s cap). */
    var rateLimitPerSecond: Int = 8

    fun channel(
        id: String,
        name: CharSequence,
        importance: Int = NotificationManagerCompat.IMPORTANCE_DEFAULT,
        configure: NotificationChannelCompat.Builder.() -> Unit = {},
    ) {
        channels.add(ChannelSpec(id, name, importance, configure))
    }

    /** Namespaces this notifier's tray ids and routes its interactions. */
    fun idSpace(tag: String, idBase: Int = 0) {
        this.tag = tag
        this.idBase = idBase
    }

    fun group(
        key: String,
        summary: (NotificationSummaryScope.(items: List<E>) -> Notification)? = null,
    ) {
        groupKey = key
        groupSummary = summary
    }

    /**
     * Maps each item's value to a notification. Called on insert, on a re-post and on a state
     * change. Return null to keep the item off the tray: its notification is cancelled, whether
     * or not this notifier posted it.
     */
    fun content(map: NotificationScope<T>.(item: T) -> Notification?) {
        content = map
    }

    /**
     * Whether a surviving item re-posts when the list delivers it again (an update, or a reload).
     * Defaults to a new instance (`old !== new`); pass value equality for snapshot items.
     */
    fun shouldRepost(predicate: (old: T, new: T) -> Boolean) {
        shouldRepost = predicate
    }

    /**
     * Adopts a notification the tray already shows at an item's address, instead of posting it
     * again, when [predicate] accepts it. Only an item's first render adopts; a hidden item
     * (null content) is cancelled instead. Without this, every first render posts.
     */
    fun adoptDelivered(predicate: (item: T, delivered: StatusBarNotification) -> Boolean = { _, _ -> true }) {
        adoptDelivered = predicate
    }

    /**
     * Optional per-item observable state; each emission re-runs [content] and re-posts the same
     * key. Without an [initial] state, an item's first render waits for its first emission.
     */
    @Suppress("UNCHECKED_CAST")
    fun <S> itemState(accessor: (T) -> Flow<S>, initial: ((T) -> S)? = null) {
        stateAccessor = accessor as (T) -> Flow<Any?>
        stateInitial = initial as ((T) -> Any?)?
    }

    fun onDismiss(handler: (item: T) -> Unit) {
        onDismiss = handler
    }

    fun onContentTap(handler: (item: T) -> Unit) {
        onContentTap = handler
    }

    fun onAction(handler: (item: T, actionKey: String) -> Unit) {
        onAction = handler
    }

    internal fun toConfig(identity: TrayIdentity): NotifierConfig<T> {
        val map = content ?: error("notifier { content { ... } } is required")
        return NotifierConfig(
            tag, idBase, identity, channels.toList(), map, adoptDelivered, groupKey, onDismiss, onContentTap, onAction,
        )
    }

    internal fun toSummary(): (NotificationSummaryScope.(List<E>) -> Notification)? = groupSummary

    internal fun toOptions(): NotifierOptions<T> =
        NotifierOptions(cancelOnUnbind, rateLimitPerSecond, shouldRepost, stateAccessor, stateInitial)
}

internal class ChannelSpec(
    val id: String,
    val name: CharSequence,
    val importance: Int,
    val configure: NotificationChannelCompat.Builder.() -> Unit,
)

/** How a notifier's items map to tray addresses. */
internal enum class TrayIdentity {
    /** Session stable ids: `notify(tag, idBase + stableId)`, keyed by the id's digits. */
    StableIds,

    /** Durable keys: `notify(tag = key, id = idBase)`. */
    Keys,
}

/** Where a notification sits in the tray: `NotificationManager`'s tag and id. */
internal data class TrayAddress(val tag: String, val id: Int)

internal class NotifierConfig<T>(
    val tag: String,
    val idBase: Int,
    val identity: TrayIdentity,
    val channels: List<ChannelSpec>,
    val content: NotificationScope<T>.(T) -> Notification?,
    val adoptDelivered: ((T, StatusBarNotification) -> Boolean)?,
    val groupKey: String?,
    val onDismiss: ((T) -> Unit)?,
    val onContentTap: ((T) -> Unit)?,
    val onAction: ((T, String) -> Unit)?,
) {
    fun address(key: String): TrayAddress = when (identity) {
        TrayIdentity.StableIds -> TrayAddress(tag, idBase + key.toInt())
        TrayIdentity.Keys -> TrayAddress(key, idBase)
    }

    fun stableIdOf(key: String): Int? = when (identity) {
        TrayIdentity.StableIds -> key.toInt()
        TrayIdentity.Keys -> null
    }

    fun defaultChannelId(): String? = channels.firstOrNull()?.id
    val summaryTag: String get() = "$tag#summary"
}

internal class NotifierOptions<T>(
    val cancelOnUnbind: Boolean,
    val rateLimitPerSecond: Int,
    val shouldRepost: (T, T) -> Boolean,
    val stateAccessor: ((T) -> Flow<Any?>)?,
    val stateInitial: ((T) -> Any?)?,
)

/**
 * What a notifier did to the tray for one item, as [DeltaNotifier.operations] reports it.
 * A caller about to lose its process (a push service, a broadcast receiver) can wait for the
 * operation on its key before returning.
 */
data class TrayOperation(val key: String, val kind: Kind) {
    enum class Kind { Posted, Adopted, Cancelled }
}

/**
 * Drives the system tray from a [DeltaList]. Mirrors `DeltaAdapter`'s bind/unbind lifecycle.
 *
 * Unlike a RecyclerView adapter, posted notifications live in the OS tray independently of
 * this binding: by default [unbind] does NOT clear them (set `cancelOnUnbind = true` or call
 * [cancelAll] for deterministic teardown). Interactions keep working after unbind because the
 * back-channel runs through durable PendingIntents to [DeltaNotificationReceiver].
 */
class DeltaNotifier<E, T> internal constructor(
    context: Context,
    private val deltaList: DeltaList<E>,
    private val config: NotifierConfig<T>,
    private val options: NotifierOptions<T>,
    summary: (NotificationSummaryScope.(List<E>) -> Notification)?,
    private val keyOf: (E) -> String,
    private val valueOf: (E) -> T,
) {
    private val reported = MutableSharedFlow<TrayOperation>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every post, adoption and cancellation, as it happens. Nothing is replayed. */
    val operations: SharedFlow<TrayOperation> = reported.asSharedFlow()

    private val sink: NotificationSink<E, T> =
        ManagerNotificationSink(context, config, summary, report = { reported.tryEmit(it) })
    private val grouped: Boolean = summary != null

    private var controller: TrayController<E, T>? = null
    private var job: Job? = null

    /** Syncs the tray while [owner] is alive. */
    fun bind(owner: LifecycleOwner): DeltaNotifier<E, T> {
        return bind(owner.lifecycleScope)
    }

    /**
     * Syncs the tray for as long as [scope] lives, such as an application scope, so the tray
     * follows the list with no screen showing. The scope must run on one thread at a time
     * (for example `Dispatchers.Main`).
     */
    fun bind(scope: CoroutineScope): DeltaNotifier<E, T> {
        unbind()
        val controller = TrayController(
            scope = scope,
            sink = sink,
            grouped = grouped,
            keyOf = keyOf,
            shouldRepost = options.shouldRepost,
            stateAccessor = options.stateAccessor,
            stateInitial = options.stateInitial,
            rateLimitPerSecond = options.rateLimitPerSecond,
            valueOf = valueOf,
        )
        this.controller = controller
        NotifierRegistry.register(config.tag, this)
        job = scope.launch {
            deltaList.collect { delta: Delta<E> -> controller.applyDelta(delta) }
        }
        return this
    }

    fun unbind() {
        NotifierRegistry.unregister(config.tag, this)
        job?.cancel()
        job = null
        if (options.cancelOnUnbind) controller?.cancelAll() else controller?.stop()
    }

    /** Remove every notification this notifier posted, and any group summary. */
    fun cancelAll() {
        controller?.cancelAll()
    }

    internal fun dispatch(interaction: NotificationInteraction): Boolean {
        val value = controller?.valueFor(interaction.key) ?: return false
        when (interaction.type) {
            NotificationInteraction.Type.Dismiss -> config.onDismiss?.invoke(value)
            NotificationInteraction.Type.ContentTap -> config.onContentTap?.invoke(value)
            NotificationInteraction.Type.Action ->
                interaction.actionKey?.let { key -> config.onAction?.invoke(value, key) }
        }
        return true
    }
}

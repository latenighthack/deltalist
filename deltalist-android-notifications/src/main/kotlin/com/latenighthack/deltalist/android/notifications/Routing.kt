package com.latenighthack.deltalist.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * A user interaction with a posted notification, decoded from the PendingIntent
 * that the system fired (dismissal, body tap, or action-button tap).
 */
data class NotificationInteraction(
    val idSpaceTag: String,
    /** The item's tray key: its durable key, or its stable id's digits. */
    val key: String,
    /** The item's session-stable id, or null for a keyed notifier. */
    val stableId: Int?,
    val type: Type,
    val actionKey: String?,
) {
    enum class Type { Dismiss, ContentTap, Action }
}

internal const val ACTION_INTERACT = "com.latenighthack.deltalist.android.notifications.INTERACT"
internal const val EXTRA_TAG = "deltalist.tag"
internal const val EXTRA_KEY = "deltalist.key"
internal const val EXTRA_STABLE_ID = "deltalist.stableId"
internal const val EXTRA_TYPE = "deltalist.type"
internal const val EXTRA_ACTION_KEY = "deltalist.actionKey"

/**
 * Process-global lookup from an id-space tag to the currently-bound notifier.
 * A notifier registers itself on [DeltaNotifier.bind] and removes itself on unbind,
 * so interactions only route to a live notifier.
 */
internal object NotifierRegistry {
    private val notifiers = ConcurrentHashMap<String, DeltaNotifier<*, *>>()

    fun register(tag: String, notifier: DeltaNotifier<*, *>) {
        notifiers[tag] = notifier
    }

    fun unregister(tag: String, notifier: DeltaNotifier<*, *>) {
        notifiers.remove(tag, notifier)
    }

    fun dispatch(interaction: NotificationInteraction): Boolean {
        val notifier = notifiers[interaction.idSpaceTag] ?: return false
        return notifier.dispatch(interaction)
    }
}

/**
 * The durable target for every notification's PendingIntents. It is declared in this
 * module's manifest (so it merges into the host app automatically) and routes each
 * interaction back to the bound notifier's lambda handlers.
 *
 * Subclass and declare your subclass in the manifest to handle a "cold" interaction —
 * one that arrives when no notifier is currently bound (e.g. the process was killed and
 * cold-started by the tap). Override [onColdInteraction] to react (enqueue work, start a
 * service, etc.).
 */
open class DeltaNotificationReceiver : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INTERACT) return

        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val stableId = intent.getIntExtra(EXTRA_STABLE_ID, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val type = intent.getStringExtra(EXTRA_TYPE)
            ?.let { runCatching { NotificationInteraction.Type.valueOf(it) }.getOrNull() }
            ?: return
        val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)

        val interaction = NotificationInteraction(tag, key, stableId, type, actionKey)
        if (!NotifierRegistry.dispatch(interaction)) {
            onColdInteraction(context, interaction)
        }
    }

    /** Called when an interaction arrives but no notifier is bound to handle it. */
    open fun onColdInteraction(context: Context, event: NotificationInteraction) {}
}

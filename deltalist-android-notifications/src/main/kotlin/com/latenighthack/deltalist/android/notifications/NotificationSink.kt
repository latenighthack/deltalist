package com.latenighthack.deltalist.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The side-effecting surface that the [TrayController] drives. Keeping it behind an
 * interface lets the controller's delta -> operation logic be unit-tested with a fake,
 * with no Android types involved in the assertions.
 *
 * @param E the list element type (carries identity); @param T the notification payload type.
 */
internal interface NotificationSink<E, T> {
    /**
     * Renders [value] under [key]. Content of null cancels the key, posted here or not. On an
     * item's [isFirst] render, a notification already in the tray may be adopted instead.
     */
    fun post(key: String, value: T, state: Any?, isFirst: Boolean)
    fun cancel(key: String)
    fun postSummary(items: List<E>)
    fun cancelSummary()
}

/**
 * Production sink: renders each item through the builder's `content` map and posts it to
 * the system tray via [NotificationManagerCompat]. Channels are created lazily and
 * idempotently on first post.
 */
internal class ManagerNotificationSink<E, T>(
    private val context: Context,
    private val config: NotifierConfig<T>,
    private val summary: (NotificationSummaryScope.(List<E>) -> Notification)?,
    private val report: (TrayOperation) -> Unit,
) : NotificationSink<E, T> {

    private val manager = NotificationManagerCompat.from(context)
    private var channelsCreated = false

    private fun ensureChannels() {
        if (channelsCreated) return
        config.channels.forEach { spec ->
            val builder = NotificationChannelCompat.Builder(spec.id, spec.importance)
                .setName(spec.name)
            spec.configure(builder)
            manager.createNotificationChannel(builder.build())
        }
        channelsCreated = true
    }

    @SuppressLint("MissingPermission")
    override fun post(key: String, value: T, state: Any?, isFirst: Boolean) {
        val scope = NotificationScope(context, key, config.stableIdOf(key), state, config)
        val notification = config.content.invoke(scope, value)
        if (notification == null) {
            cancel(key)
            return
        }
        val address = config.address(key)
        val adopt = config.adoptDelivered
        if (isFirst && adopt != null) {
            val delivered = delivered(address)
            if (delivered != null && adopt(value, delivered)) {
                report(TrayOperation(key, TrayOperation.Kind.Adopted))
                return
            }
        }
        if (!manager.areNotificationsEnabled()) return
        ensureChannels()
        manager.notify(address.tag, address.id, notification)
        report(TrayOperation(key, TrayOperation.Kind.Posted))
    }

    override fun cancel(key: String) {
        val address = config.address(key)
        manager.cancel(address.tag, address.id)
        report(TrayOperation(key, TrayOperation.Kind.Cancelled))
    }

    @SuppressLint("MissingPermission")
    override fun postSummary(items: List<E>) {
        val summary = summary ?: return
        val groupKey = config.groupKey ?: return
        ensureChannels()
        val scope = NotificationSummaryScope(context, groupKey, config.defaultChannelId())
        val notification = summary.invoke(scope, items)
        manager.notify(config.summaryTag, SUMMARY_ID, notification)
    }

    override fun cancelSummary() {
        manager.cancel(config.summaryTag, SUMMARY_ID)
    }

    // The app's own notification at this address, if the tray still shows it.
    private fun delivered(address: TrayAddress): StatusBarNotification? {
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?: return null
        return notifications.firstOrNull { it.tag == address.tag && it.id == address.id }
    }

    private companion object {
        const val SUMMARY_ID = 0
    }
}

package com.latenighthack.deltalist.android.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerNotificationSinkTest {
    private data class Push(val notificationId: String, val title: String, val isShown: Boolean = true)

    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private val operations = mutableListOf<TrayOperation>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun sink(adopt: ((Push, android.service.notification.StatusBarNotification) -> Boolean)?) =
        ManagerNotificationSink<Push, Push>(
            context,
            NotifierConfig(
                tag = "requests",
                idBase = 0,
                identity = TrayIdentity.Keys,
                channels = listOf(ChannelSpec("requests", "Requests", NotificationManagerCompat.IMPORTANCE_HIGH) {}),
                content = { push ->
                    if (!push.isShown) null else notification { setSmallIcon(android.R.drawable.ic_delete); setContentTitle(push.title) }
                },
                adoptDelivered = adopt,
                groupKey = null,
                onDismiss = null,
                onContentTap = null,
                onAction = null,
            ),
            summary = null,
            report = { operations.add(it) },
        )

    // As the app's own push handling, or an earlier process, would have left it.
    private fun deliver(tag: String, title: String) {
        val notification = Notification.Builder(context, "requests")
            .setSmallIcon(android.R.drawable.ic_delete)
            .setContentTitle(title)
            .build()
        manager.notify(tag, 0, notification)
    }

    private fun titles() = manager.activeNotifications.associate {
        it.tag to it.notification.extras.getString(Notification.EXTRA_TITLE)
    }

    @Test fun postsUnderTheKeyAsTag() {
        sink(adopt = null).post("pnt_a", Push("pnt_a", "a"), null, isFirst = true)

        assertEquals(mapOf("pnt_a" to "a"), titles())
        assertEquals(listOf(TrayOperation("pnt_a", TrayOperation.Kind.Posted)), operations)
    }

    @Test fun firstRenderAdoptsADeliveredNotificationWithoutPostingAgain() {
        deliver("pnt_a", "delivered")

        sink(adopt = { _, _ -> true }).post("pnt_a", Push("pnt_a", "rendered"), null, isFirst = true)

        assertEquals(mapOf("pnt_a" to "delivered"), titles())
        assertEquals(listOf(TrayOperation("pnt_a", TrayOperation.Kind.Adopted)), operations)
    }

    @Test fun laterRendersAndRejectedNotificationsPost() {
        deliver("pnt_a", "delivered")
        val sink = sink(adopt = { push, delivered -> delivered.notification.extras.getString(Notification.EXTRA_TITLE) == push.title })

        sink.post("pnt_a", Push("pnt_a", "other"), null, isFirst = true)
        assertEquals(mapOf("pnt_a" to "other"), titles())
        sink.post("pnt_a", Push("pnt_a", "later"), null, isFirst = false)
        assertEquals(mapOf("pnt_a" to "later"), titles())
        assertTrue(operations.all { it.kind == TrayOperation.Kind.Posted })
    }

    @Test fun nullContentCancelsANotificationThisSinkNeverPosted() {
        deliver("pnt_a", "delivered")

        sink(adopt = { _, _ -> true }).post("pnt_a", Push("pnt_a", "a", isShown = false), null, isFirst = true)

        assertEquals(emptyMap<String, String>(), titles())
        assertEquals(listOf(TrayOperation("pnt_a", TrayOperation.Kind.Cancelled)), operations)
    }
}

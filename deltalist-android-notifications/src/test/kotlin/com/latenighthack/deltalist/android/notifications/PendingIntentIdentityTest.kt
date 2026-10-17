package com.latenighthack.deltalist.android.notifications

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

internal fun testConfig(tag: String, identity: TrayIdentity = TrayIdentity.StableIds) = NotifierConfig<String>(
    tag, 0, identity, emptyList(), { notification("test") { } }, null, null, null, null, null,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingIntentIdentityTest {
    @Test fun idSpacesMustNotSharePendingIntent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun notification(tag: String) = NotificationScope(activity, "7", 7, null, testConfig(tag))
            .notification("test") { setSmallIcon(android.R.drawable.ic_delete) }
        try {
            val a = notification("a")
            val b = notification("b")
            assertEquals("a", shadowOf(a.contentIntent).savedIntent.getStringExtra(EXTRA_TAG))
            assertNotEquals(a.contentIntent, b.contentIntent)
        } finally { activity.finish() }
    }

    @Test fun collidingActionKeysMustNotSharePendingIntent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val scope = NotificationScope(activity, "7", 7, null, testConfig("actions"))
            val notification = with(scope) { notification("test") {
                setSmallIcon(android.R.drawable.ic_delete)
                action(android.R.drawable.ic_delete, "First", "Aa")
                action(android.R.drawable.ic_delete, "Second", "BB")
            } }
            assertNotEquals(notification.actions[0].actionIntent, notification.actions[1].actionIntent)
        } finally { activity.finish() }
    }

    @Test fun keyedItemsGetTheirOwnPendingIntentsAndCarryTheirKey() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun notification(key: String) = NotificationScope(activity, key, null, null, testConfig("requests", TrayIdentity.Keys))
            .notification("test") { setSmallIcon(android.R.drawable.ic_delete) }
        try {
            val a = notification("pnt_a")
            val b = notification("pnt_b")
            val saved = shadowOf(a.contentIntent).savedIntent
            assertEquals("requests", saved.getStringExtra(EXTRA_TAG))
            assertEquals("pnt_a", saved.getStringExtra(EXTRA_KEY))
            assertFalse(saved.hasExtra(EXTRA_STABLE_ID))
            assertNotEquals(a.contentIntent, b.contentIntent)
        } finally { activity.finish() }
    }
}

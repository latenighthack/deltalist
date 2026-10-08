package com.latenighthack.deltalist.android.notifications

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingIntentIdentityTest {
    @Test fun idSpacesMustNotSharePendingIntent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun notification(tag: String) = NotificationScope(activity, 7, null,
            NotifierConfig<String>(tag, 0, emptyList(), { notification("test") { } }, null, null, null, null))
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
            val scope = NotificationScope(activity, 7, null,
                NotifierConfig<String>("actions", 0, emptyList(), { notification("test") { } }, null, null, null, null))
            val notification = with(scope) { notification("test") {
                setSmallIcon(android.R.drawable.ic_delete)
                action(android.R.drawable.ic_delete, "First", "Aa")
                action(android.R.drawable.ic_delete, "Second", "BB")
            } }
            assertNotEquals(notification.actions[0].actionIntent, notification.actions[1].actionIntent)
        } finally { activity.finish() }
    }
}

package app.homeroom

import android.app.NotificationManager
import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The "Hide lock-screen alerts" setting must reach the notifications that are actually posted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Hr18PrivacyTest {
    private fun enrolled():Session {
        val app=RuntimeEnvironment.getApplication()
        val s=Session(app);s.clear();s.ownerId="owner";s.sessionId="phone";s.cookie="c";assertTrue(s.enrolled)
        PrivacyPrefs.set(app,"hide_previews",false)
        return s
    }
    private fun manager()=RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test fun genericPushNotificationFollowsHideSetting() {
        val app=RuntimeEnvironment.getApplication();enrolled()
        val service=Robolectric.setupService(HomeRoomMessagingService::class.java)
        val msg=RemoteMessage.Builder("a@gcm.googleapis.com").setMessageId("m1").setData(mapOf("kind" to "new_message")).build()
        service.onMessageReceived(msg)
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC,shadowOf(manager()).allNotifications.single().visibility)
        manager().cancelAll()
        PrivacyPrefs.set(app,"hide_previews",true)
        service.onMessageReceived(RemoteMessage.Builder("a@gcm.googleapis.com").setMessageId("m2").setData(mapOf("kind" to "new_message")).build())
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_SECRET,shadowOf(manager()).allNotifications.single().visibility)
    }

    @Test fun approvalNotificationFollowsHideSetting() {
        val app=RuntimeEnvironment.getApplication();enrolled()
        ApprovalNotifications.show(app,mapOf("kind" to "key_pending","key_id" to "k1"))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,shadowOf(manager()).allNotifications.single().visibility)
        manager().cancelAll()
        PrivacyPrefs.set(app,"hide_previews",true)
        ApprovalNotifications.show(app,mapOf("kind" to "key_pending","key_id" to "k1"))
        assertEquals(android.app.Notification.VISIBILITY_SECRET,shadowOf(manager()).allNotifications.single().visibility)
    }

    @Test fun visibilityHelpersFollowSetting() {
        val app=RuntimeEnvironment.getApplication();enrolled()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,PrivacyPrefs.visibility(app))
        PrivacyPrefs.set(app,"hide_previews",true)
        assertEquals(android.app.Notification.VISIBILITY_SECRET,PrivacyPrefs.visibility(app))
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_SECRET,HomeRoomMessagingService.genericVisibility(app))
    }

    @Test fun fetchWorkerDiscardsWorkQueuedUnderAnotherSession() {
        assertTrue(NotificationFetchWorker.staleSession("A","B"))
        assertFalse(NotificationFetchWorker.staleSession("B","B"))
        assertFalse("older enqueue without an id still runs",NotificationFetchWorker.staleSession(null,"B"))
        assertTrue(NotificationFetchWorker.staleSession("A",null))
    }
}

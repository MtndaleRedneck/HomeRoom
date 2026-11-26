package app.homeroom

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class NotificationLockGuardTest {
    private lateinit var context:Context
    @Before fun setUp() {
        context=RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        PrivacyPrefs.set(context,"lock",false)
        Session(context).apply {serverUrl="https://server.example";cookie="cookie";sessionId="s1"}
    }
    private fun actionIntent(action:String)=Intent(action)
        .putExtra("room","room-a").putExtra("event","e1").putExtra("session","s1")
    private fun replyIntent():Intent {
        val intent=actionIntent("reply")
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(NotificationActions.REPLY_TEXT).build()),intent,
            Bundle().apply {putCharSequence(NotificationActions.REPLY_TEXT,"on my way")})
        return intent
    }
    private fun enqueued(name:String)=WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().isNotEmpty()

    // Both locked cases use inputs that WOULD enqueue without the guard: a valid
    // read action and a reply carrying real RemoteInput text. Without the guard
    // each reaches the enqueue call, so the assertion is not vacuous.
    @Test fun lockedReadDoesNotEnqueue() {
        PrivacyPrefs.set(context,"lock",true)
        NotificationActionReceiver().onReceive(context,actionIntent("read"))
        assertFalse(enqueued("notification-action:room-a:read"))
    }
    @Test fun lockedReplyWithTextDoesNotEnqueue() {
        PrivacyPrefs.set(context,"lock",true)
        NotificationActionReceiver().onReceive(context,replyIntent())
        assertFalse(enqueued("notification-action:room-a:reply"))
    }
    @Test fun unlockedSessionStillEnqueuesAction() {
        NotificationActionReceiver().onReceive(context,actionIntent("read"))
        assertTrue(enqueued("notification-action:room-a:read"))
    }
}

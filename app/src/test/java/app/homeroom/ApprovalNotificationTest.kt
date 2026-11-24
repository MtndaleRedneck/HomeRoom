package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import android.app.NotificationManager
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class ApprovalNotificationTest {
    private fun notification():android.app.Notification {
        val ctx=RuntimeEnvironment.getApplication()
        Session(ctx).apply {clear();serverUrl="https://fixture.example";cookie="fixture";sessionId="fixture"}
        ApprovalNotifications.show(ctx,mapOf("kind" to "rule_pending","rule_id" to "fixture-rule","version" to "1","room_id" to "fixture-room"))
        return (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.first {it.tag=="approval:fixture-rule"}.notification
    }
    @Test @Config(sdk=[34]) fun modernDecisionActionsRequireUnlock() {
        val actions=notification().actions
        assertEquals(listOf("Review","Reject","Approve"),actions.map {it.title.toString()})
        assertTrue(actions[1].isAuthenticationRequired)
        assertTrue(actions[2].isAuthenticationRequired)
    }
    @Test @Config(sdk=[30]) fun olderAndroidReviewOnly() {
        assertEquals(listOf("Review"),notification().actions.map {it.title.toString()})
    }
    @Test @Config(sdk=[34]) fun onlyWebSchemesAreAutoLinked() {
        val s=Markdown.render("https://example.com tel:+12025550123 mailto:a@example.com geo:0,0 sms:+12025550123")
        val spans=s.getSpans(0,s.length,android.text.style.URLSpan::class.java)
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all {it.url.startsWith("https://") || it.url.startsWith("http://")})
    }
}
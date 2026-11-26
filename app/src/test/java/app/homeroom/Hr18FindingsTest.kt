package app.homeroom

import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Four pre-existing defects found by the 4453d11 model rounds, plus the feedback id.
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Hr18FindingsTest {
    private fun field(o:Any,n:String):java.lang.reflect.Field=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    private fun room(id:String,name:String)=Room.from(JSONObject("""{"room_id":"$id","display_name":"$name","members":"[]","main_thread_key":"main$id","unread_count":0,"pending_rules_count":0}"""))
    private fun start():org.robolectric.android.controller.ActivityController<MainActivity> {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        val a=c.get()
        field(a,"polling").setBoolean(a,false)
        a.session.ownerId="owner";a.session.ownerName="Owner";a.session.serverUrl="https://home-server.example";a.session.sessionId="phone"
        a.session.cookie="test-cookie"
        return c
    }
    private fun shareIntent(a:MainActivity)=Intent(a,MainActivity::class.java).setAction(Intent.ACTION_SEND)
        .putExtra(Intent.EXTRA_STREAM,android.net.Uri.parse("content://app.homeroom.test/f/1"))
    private fun showingDialogs()=shadowOf(android.os.Looper.getMainLooper()).let {org.robolectric.shadows.ShadowDialog.getShownDialogs()}.filter {it.isShowing}

    // 1. Foreground flag: true only while resumed-and-started, false once stopped; a pause alone (prompt over the app) keeps it.
    @Test fun foregroundFlagClearsOnStopNotOnPause() {
        val c=start()
        assertTrue(MainActivity.isForeground)
        c.pause()
        assertTrue("a prompt over the visible app is not background",MainActivity.isForeground)
        c.stop()
        assertFalse("stopped app is background: pushes must show",MainActivity.isForeground)
        c.start().resume()
        assertTrue(MainActivity.isForeground)
    }

    // 1b. Enrollment finishing while backgrounded is not stranded: Main shows on the next resume.
    @Test fun backgroundEnrollShowsMainOnResume() {
        val c=start()
        val a=c.get()
        c.pause().stop()
        field(a,"pendingShowMain").setBoolean(a,true)
        c.start().resume()
        assertFalse(field(a,"pendingShowMain").getBoolean(a))
    }

    // 3a. With the lock on, every tracked dialog is closed before the cover goes up; with it off they stay.
    @Test fun lockOnPauseClosesTrackedDialogs() {
        val c=start();val a=c.get()
        val d=android.app.AlertDialog.Builder(a,R.style.Theme_HomeRoom_Dialog).setMessage("private").showTracked()
        assertTrue(d.isShowing)
        c.pause()
        assertTrue("lock off: dialog untouched",d.isShowing)
        c.resume()
        PrivacyPrefs.set(a,"lock",true)
        try {
            c.pause()
            assertFalse("lock on: dialog closed before the cover",d.isShowing)
        } finally {PrivacyPrefs.set(a,"lock",false)}
    }

    // 3b. A share arriving while locked builds no picker until unlock; the Uri waits in retained state.
    @Test fun shareWhileLockedWaitsForUnlock() {
        val c=start();val a=c.get()
        a.cacheRooms(listOf(room("rA","Room A")))
        PrivacyPrefs.set(a,"lock",true)
        try {
            c.pause()
            assertTrue(a.appLock.coverShowing)
            a.onNewIntent(shareIntent(a))
            assertNotNull("uri queued",a.retained.pendingShare)
            assertTrue("no room names above the cover",showingDialogs().none {it.isShowing && it !== null && (it as? android.app.AlertDialog)?.listView?.count==1})
            val unlock=a.appLock.javaClass.getDeclaredMethod("success").apply {isAccessible=true}
            unlock.invoke(a.appLock)
            assertNull("picker consumed the queue",a.retained.pendingShare)
            assertTrue("picker shown after unlock",showingDialogs().any {(it as? android.app.AlertDialog)?.listView?.count==1})
        } finally {PrivacyPrefs.set(a,"lock",false)}
    }

    // 3c. Unlocked: the picker appears at once.
    @Test fun shareWhenUnlockedShowsPicker() {
        val c=start();val a=c.get()
        a.cacheRooms(listOf(room("rA","Room A")))
        a.onNewIntent(shareIntent(a))
        assertTrue(showingDialogs().any {(it as? android.app.AlertDialog)?.listView?.count==1})
        assertNull(a.retained.pendingShare)
    }

    // 4. Manual update: busy is released for install(), and a second check is still ignored while one runs.
    @Test fun manualCheckReleasesBusyOnlyForInstall() {
        val busy=UpdateCheck::class.java.getDeclaredField("busy").apply {isAccessible=true}
        val inst=UpdateCheck
        busy.setBoolean(inst,true)
        try {
            val c=start();val a=c.get()
            var called=0
            UpdateCheck.checkManually(a,a.scope,a.session) {called++}
            assertEquals("a running check blocks a second one",0,called)
        } finally {busy.setBoolean(inst,false)}
    }

    // 5. Feedback id: same text keeps its id, new text or a confirmed send gets a new one.
    @Test fun feedbackIdPolicy() {
        val c=start();val a=c.get()
        val s=FeedbackScreen(a)
        val m=s.javaClass.getDeclaredMethod("clientIdFor",String::class.java).apply {isAccessible=true}
        val one=m.invoke(s,"first") as String
        assertEquals("retry of the same text replays",one,m.invoke(s,"first"))
        assertNotEquals("new text, new report",one,m.invoke(s,"second"))
        // Recreated screen (font-size change) keeps the identity.
        val kept=m.invoke(s,"second") as String
        assertEquals(kept,m.invoke(FeedbackScreen(a),"second"))
        // Confirmed success clears it.
        a.retained.feedbackClientId=null;a.retained.feedbackSentText=null
        assertNotEquals(kept,m.invoke(s,"second"))
    }
}

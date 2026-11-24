package app.homeroom

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Finite closure tests for the I2 76e narrow privacy trace and ChatGPT B4:
// entering Setup discards pushed pages (an unconsumed Back on SetupScreen
// must never re-show a pre-reset page), a reset drops queued after-unlock
// actions (they never fire), and an off-main runWhenUnlocked submission
// posted-but-not-yet-run at reset time is dropped by its captured session
// generation (it never entered the queue, so the queue clear alone could
// not catch it).
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SetupResetTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}

    private fun enrolledPrefs() {
        val s = Session(org.robolectric.RuntimeEnvironment.getApplication())
        s.clear()
        s.serverUrl = "https://stub.invalid"
        s.cookie = "c"
        s.csrf = "t"
        s.sessionId = "sid"
    }

    @Test fun setupEntryDiscardsPushedPages() {
        enrolledPrefs()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(a,"polling").setBoolean(a,false)
        // Push a real page over the tab root, as openRoom/openPage do.
        a.openPage("Page", SetupScreen(a))
        assertTrue((field(a,"pageStack").get(a) as java.util.ArrayDeque<*>).isNotEmpty())
        // Sign-out contract: generation bump first (SettingsScreen), then setup.
        Session(org.robolectric.RuntimeEnvironment.getApplication()).clear()
        a.onSignedOut()
        assertTrue("setup entry discards pushed pages",
            (field(a,"pageStack").get(a) as java.util.ArrayDeque<*>).isEmpty())
        // Back must not re-show the pre-reset page; the setup screen stays visible.
        a.onBackPressedDispatcher.onBackPressed()
        val visible = (field(a,"content").get(a) as android.widget.FrameLayout).getChildAt(0)
        assertTrue(visible is SetupScreen)
    }

    @Test fun resetDropsQueuedAfterUnlockActions() {
        enrolledPrefs()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(a,"polling").setBoolean(a,false)
        PrivacyPrefs.set(a,"lock",true)
        assertTrue(a.appLock.gate())  // cover up, unauthenticated
        var ran = false
        a.runWhenUnlocked { ran = true }  // main thread, cover showing -> queued
        assertFalse(ran)
        // Reset before any unlock: the queued action is discarded...
        Session(org.robolectric.RuntimeEnvironment.getApplication()).clear()
        a.onSignedOut()
        // ...and a later unlock must not fire it.
        a.appLock.javaClass.getDeclaredMethod("success").apply{isAccessible=true}.invoke(a.appLock)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertFalse("queued action from the old session never runs", ran)
    }

    @Test fun offMainSubmissionDroppedAcrossSessionReset() {
        enrolledPrefs()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(a,"polling").setBoolean(a,false)
        shadowOf(Looper.getMainLooper()).pause()  // hold the posted runnable
        var ran = false
        val t = Thread { a.runWhenUnlocked { ran = true } }  // off-main: posts, returns
        t.start(); t.join()
        Session(org.robolectric.RuntimeEnvironment.getApplication()).clear()  // reset BEFORE the post runs
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse("posted-but-unqueued action must not survive the reset", ran)
    }

    @Test fun offMainSubmissionRunsWithoutReset() {
        enrolledPrefs()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(a,"polling").setBoolean(a,false)
        PrivacyPrefs.set(a,"lock",false)  // no cover: action runs on the main thread
        shadowOf(Looper.getMainLooper()).pause()
        var ran = false
        val t = Thread { a.runWhenUnlocked { ran = true } }
        t.start(); t.join()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertTrue("same-session submission still runs (liveness)", ran)
    }
}

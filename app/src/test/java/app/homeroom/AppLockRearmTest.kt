package app.homeroom

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Finite coverage for the AppLock pause correction (MainActivity.onPause now
// calls appLock.onPause - previously never invoked) and the explicit
// pause-block on the nav backdrop capture (I2, Nov 24): background re-arms
// the lock, a pending auth result suppresses the re-arm, a capture queued
// before the pause never runs after it, and while paused (no cover, no
// FLAG_SECURE) no new capture proceeds.
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AppLockRearmTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}
    private fun succeed(lock:AppLock)=lock.javaClass.getDeclaredMethod("success").apply{isAccessible=true}.invoke(lock)
    private fun idle()=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))

    @Test fun pauseRearmsLockAndPendingAuthSuppresses() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        val a=c.get()
        field(a,"polling").setBoolean(a,false)
        val lock=a.appLock
        PrivacyPrefs.set(a,"lock",true)
        try {
            // Foreground with lock enabled and not authenticated: gate covers.
            assertTrue(lock.gate())
            assertTrue(lock.coverShowing)
            // Successful auth clears the cover and marks authenticated.
            succeed(lock)
            field(a,"polling").setBoolean(a,false)
            assertFalse(lock.coverShowing)
            assertTrue(field(lock,"authenticated").getBoolean(lock))
            // Background: onPause re-arms (authenticated cleared) and the
            // cover is up before the next resume gate.
            c.pause()
            assertFalse("pause re-arms the lock",field(lock,"authenticated").getBoolean(lock))
            assertTrue("pause re-covers while locked",lock.coverShowing)
            // Foreground: the gate keeps the cover until auth succeeds.
            c.resume()
            assertTrue(lock.coverShowing)
            succeed(lock)
            field(a,"polling").setBoolean(a,false)
            assertFalse(lock.coverShowing)
            // A pause DURING authentication (result pending) must not re-arm
            // or re-cover; the arriving result then decides.
            field(lock,"authenticating").setBoolean(lock,true)
            c.pause()
            assertTrue("pending auth result keeps the session authenticated",field(lock,"authenticated").getBoolean(lock))
            assertFalse("pending auth result shows no new cover",lock.coverShowing)
            field(lock,"authenticating").setBoolean(lock,false)
            succeed(lock)
            field(a,"polling").setBoolean(a,false)
            assertFalse(lock.coverShowing)
        } finally {
            PrivacyPrefs.set(a,"lock",false)
        }
        c.pause()
    }

    @Test fun queuedCaptureInvalidatedByPause() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        val a=c.get()
        field(a,"polling").setBoolean(a,false)
        val nav=field(a,"nav").get(a) as GlassNav
        val frame=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        nav.visibility=View.VISIBLE
        frame.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));frame.layout(0,0,360,780)
        // Baseline capture, then enqueue a second and pause before it runs.
        nav.backdropChanged();idle()
        assertEquals(1,nav.captureCount)
        nav.backdropChanged()
        c.pause()
        idle()
        assertEquals("pause invalidates the queued capture",1,nav.captureCount)
        assertNull(field(nav,"buffers").get(nav))
        // While paused - no cover, no FLAG_SECURE - a layout/scroll-triggered
        // dirty must still not capture: the explicit pause-block state.
        nav.backdropChanged();idle()
        assertEquals("explicit pause-block holds without cover or FLAG_SECURE",1,nav.captureCount)
        // Resume unblocks; the fresh capture proceeds.
        c.resume()
        field(a,"polling").setBoolean(a,false)
        idle()
        assertEquals(2,nav.captureCount)
        c.pause()
    }
}

package app.homeroom

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Focused regression for the ChatGPT finite-review finding on 24c1422: a
// system-picker result launched from one session must never reach an
// AttachmentComposer under a changed session. Pins the extracted gate
// (RoomScreen.shouldDeliverPick) against the Session generation/destination
// doctrine: clear() bumps the generation, so any teardown or re-enroll
// invalidates the launch snapshot.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerSessionGuardTest {

    private fun newSession(): Session {
        val s = Session(org.robolectric.RuntimeEnvironment.getApplication())
        s.clear()
        s.serverUrl = "https://stub.invalid"
        s.cookie = "c"
        s.csrf = "t"
        s.sessionId = "s"
        return s
    }

    private val pick: Uri = Uri.parse("content://stub/picked-file")

    @Test
    fun pickDeliveredWhenSessionUnchanged() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertTrue(RoomScreen.shouldDeliverPick(pick, s, launch, featureOn = true))
    }

    @Test
    fun pickDroppedAfterSessionEnd() {
        val s = newSession()
        val launch = s.authSnapshot()
        s.clear()
        assertFalse(RoomScreen.shouldDeliverPick(pick, s, launch, featureOn = true))
    }

    @Test
    fun pickDroppedAfterReEnrollSameDestination() {
        val s = newSession()
        val launch = s.authSnapshot()
        s.clear()
        s.serverUrl = "https://stub.invalid"
        s.cookie = "c2"
        s.csrf = "t2"
        s.sessionId = "s2"
        assertFalse(RoomScreen.shouldDeliverPick(pick, s, launch, featureOn = true))
    }

    @Test
    fun pickDroppedWhenDestinationChanges() {
        val s = newSession()
        val launch = s.authSnapshot()
        s.serverUrl = "https://other.invalid"
        assertFalse(RoomScreen.shouldDeliverPick(pick, s, launch, featureOn = true))
    }

    @Test
    fun cancelledPickNeverDelivered() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertFalse(RoomScreen.shouldDeliverPick(null, s, launch, featureOn = true))
    }

    // Room-tools menu dispatch gate (I2 focused check): a retained dialog's
    // entries must re-prove session identity and the feature flag.
    @Test
    fun menuEntryDispatchesForUnchangedSessionWithFeature() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertTrue(RoomScreen.shouldDispatchEntry(s, launch, featureOn = true, locked = false))
    }

    @Test
    fun menuEntryDroppedAfterSessionReset() {
        val s = newSession()
        val launch = s.authSnapshot()
        s.clear()
        s.serverUrl = "https://stub.invalid"
        s.cookie = "c2"
        assertFalse(RoomScreen.shouldDispatchEntry(s, launch, featureOn = true, locked = false))
    }

    @Test
    fun menuEntryDroppedWhenFeatureWithdrawn() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertFalse(RoomScreen.shouldDispatchEntry(s, launch, featureOn = false, locked = false))
    }

    @Test
    fun pickDroppedWhenFeatureWithdrawn() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertFalse(RoomScreen.shouldDeliverPick(pick, s, launch, featureOn = false))
    }

    @Test
    fun menuEntryDroppedWhileLockCoverShowing() {
        val s = newSession()
        val launch = s.authSnapshot()
        assertFalse(RoomScreen.shouldDispatchEntry(s, launch, featureOn = true, locked = true))
    }
}

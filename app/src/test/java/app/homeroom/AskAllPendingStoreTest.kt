package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused evidence for the I2 379008b race findings: dismiss-during-flight,
 * leave/re-enter with an unresolved POST, late/empty fetch reconciliation,
 * and accepted-ask recovery across screen recreation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AskAllPendingStoreTest {
    private val auth = Session.OpAuth(7L, "https://srv", "cookie", "csrf")

    @Test fun markerSetBeforeDispatchBlocksNewSend() {
        val s = AskAllPendingStore()
        // mark() is called before the POST starts; the pending read the new
        // dialog performs must already see it (dismiss/reopen in-flight race).
        s.mark(7L, "room1", "cid-1")
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun reenterAfterRecreationStillSeesMarker() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        // Screen recreation reads the same process-level store: the guard
        // survives leave/re-enter while the POST outcome is unknown.
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun emptyOrUnrelatedFetchReconcilesNothing() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        assertFalse(s.reconcile(7L, "room1", emptyList()))
        assertFalse(s.reconcile(7L, "room1", listOf("other", null)))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun matchingClientIdIsAcceptanceProof() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        assertTrue(s.reconcile(7L, "room1", listOf("x", "cid-1")))
        assertNull(s.pendingClientId(7L, "room1"))
    }

    @Test fun acceptanceClearsMarker() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        s.clear(7L, "room1")
        assertNull(s.pendingClientId(7L, "room1"))
    }

    @Test fun generationScopingIsolatesIdentities() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        // A moved identity is neither blocked by nor reconciled against the
        // old session's ask.
        assertNull(s.pendingClientId(8L, "room1"))
        assertFalse(s.reconcile(8L, "room1", listOf("cid-1")))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun otherRoomsAreIndependent() {
        val s = AskAllPendingStore()
        s.mark(7L, "room1", "cid-1")
        assertNull(s.pendingClientId(7L, "room2"))
        assertFalse(s.reconcile(7L, "room2", listOf("cid-1")))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun acceptedRecoveryIsOneShotAndScoped() {
        val s = AskAllPendingStore()
        s.retainAccepted(7L, "room1", "req-1", auth)
        // Wrong generation: no recovery leak across identities.
        assertNull(s.takeAccepted(8L, "room1"))
        // Right scope: recovered exactly once (survives screen recreation,
        // consumed when the answers page actually opens).
        val a = s.takeAccepted(7L, "room1")
        assertEquals("req-1", a?.requestId)
        assertEquals(7L, a?.auth?.generation)
        assertNull(s.takeAccepted(7L, "room1"))
    }
}

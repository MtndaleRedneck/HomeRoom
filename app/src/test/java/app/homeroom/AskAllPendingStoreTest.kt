package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused store-level evidence (NOT UI race coverage; that is the CI
 * debug-interaction race probe). Covers the I2 379008b + f409710 mechanisms:
 * mark-before-dispatch, reentry retention, empty/late fetch no-op, matching
 * client_id clears, A/B marker compare-remove, accepted queue order,
 * peek/consume-on-open, and generation/destination scoping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AskAllPendingStoreTest {

    @Test fun claimSetBeforeDispatchBlocksNewSend() {
        val s = AskAllPendingStore()
        // claim() is made before the POST starts; the pending read the new
        // dialog performs must already see it (dismiss/reopen in-flight race).
        assertTrue(s.claim(7L, "room1", "cid-1"))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun competingClaimsOnlyFirstWins() {
        val s = AskAllPendingStore()
        // Two dialogs/screens both passed the beginAskAll check (GPT/Gem
        // f409 #3): the second claim with a DIFFERENT client_id loses and
        // the original marker is never overwritten.
        assertTrue(s.claim(7L, "room1", "cid-A"))
        assertFalse(s.claim(7L, "room1", "cid-B"))
        assertEquals("cid-A", s.pendingClientId(7L, "room1"))
    }

    @Test fun sameClientIdReclaimIsIdempotent() {
        val s = AskAllPendingStore()
        // The release's in-dialog retry resends the identical body: the
        // retry's claim must succeed, not be treated as competition.
        assertTrue(s.claim(7L, "room1", "cid-A"))
        assertTrue(s.claim(7L, "room1", "cid-A"))
        assertEquals("cid-A", s.pendingClientId(7L, "room1"))
    }

    @Test fun claimSucceedsAfterCompareRemove() {
        val s = AskAllPendingStore()
        assertTrue(s.claim(7L, "room1", "cid-A"))
        assertTrue(s.clearIfMatches(7L, "room1", "cid-A"))
        assertTrue(s.claim(7L, "room1", "cid-B"))
        assertEquals("cid-B", s.pendingClientId(7L, "room1"))
    }

    @Test fun reenterAfterRecreationStillSeesMarker() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        // Screen recreation reads the same process-level store: the guard
        // survives leave/re-enter while the POST outcome is unknown.
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun emptyOrUnrelatedFetchReconcilesNothing() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        assertFalse(s.reconcile(7L, "room1", emptyList()))
        assertFalse(s.reconcile(7L, "room1", listOf("other", null)))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun matchingClientIdIsAcceptanceProof() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        assertTrue(s.reconcile(7L, "room1", listOf("x", "cid-1")))
        assertNull(s.pendingClientId(7L, "room1"))
    }

    @Test fun acceptanceCompareRemoveClearsOnlyOwnMarker() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        assertTrue(s.clearIfMatches(7L, "room1", "cid-1"))
        assertNull(s.pendingClientId(7L, "room1"))
    }

    @Test fun oldCompletionCannotEraseNewerMarker() {
        val s = AskAllPendingStore()
        // A overridden, B marked; A's late acceptance must not erase B.
        s.claim(7L, "room1", "cid-A")
        assertTrue(s.clearIfMatches(7L, "room1", "cid-A")) // user override of A
        s.claim(7L, "room1", "cid-B")
        assertFalse(s.clearIfMatches(7L, "room1", "cid-A")) // A's late completion
        assertEquals("cid-B", s.pendingClientId(7L, "room1"))
        // B's own reconcile still works.
        assertTrue(s.reconcile(7L, "room1", listOf("cid-B")))
        assertNull(s.pendingClientId(7L, "room1"))
    }

    @Test fun generationScopingIsolatesIdentities() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        // A moved identity is neither blocked by nor reconciled against the
        // old session's ask.
        assertNull(s.pendingClientId(8L, "room1"))
        assertFalse(s.reconcile(8L, "room1", listOf("cid-1")))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun otherRoomsAreIndependent() {
        val s = AskAllPendingStore()
        s.claim(7L, "room1", "cid-1")
        assertNull(s.pendingClientId(7L, "room2"))
        assertFalse(s.reconcile(7L, "room2", listOf("cid-1")))
        assertEquals("cid-1", s.pendingClientId(7L, "room1"))
    }

    @Test fun acceptedQueueRetainsBothInOrder() {
        val s = AskAllPendingStore()
        // A accepted and retained; B accepted before A's page ever opened.
        // The single-slot overwrite must not lose A (I2 f409710 #2).
        s.retainAccepted(7L, "room1", "req-A", "https://srv")
        s.retainAccepted(7L, "room1", "req-B", "https://srv")
        assertEquals("req-A", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertTrue(s.dropAccepted(7L, "room1", "req-A"))
        assertEquals("req-B", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertTrue(s.dropAccepted(7L, "room1", "req-B"))
        assertNull(s.peekAccepted(7L, "room1", "https://srv"))
    }

    @Test fun peekDoesNotConsumeBeforeOpen() {
        val s = AskAllPendingStore()
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        // Two reads (failed open, then retry) see the same entry (I2 #3).
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertFalse(s.dropAccepted(7L, "room1", "req-other"))
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
    }

    @Test fun acceptedRecoveryRejectsMovedDestination() {
        val s = AskAllPendingStore()
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        // Same generation, different destination: no cross-scope recovery.
        assertNull(s.peekAccepted(7L, "room1", "https://other"))
        // Right scope still recovers, and carries no credentials.
        val a = s.peekAccepted(7L, "room1", "https://srv")
        assertEquals("req-1", a?.requestId)
        assertEquals(7L, a?.generation)
        assertEquals("https://srv", a?.serverUrl)
    }
}

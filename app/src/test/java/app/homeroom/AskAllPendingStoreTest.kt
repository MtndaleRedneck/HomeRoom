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
        assertTrue(s.dropAccepted(7L, "room1", "req-A", "https://srv"))
        assertEquals("req-B", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertTrue(s.dropAccepted(7L, "room1", "req-B", "https://srv"))
        assertNull(s.peekAccepted(7L, "room1", "https://srv"))
    }

    @Test fun peekDoesNotConsumeBeforeOpen() {
        val s = AskAllPendingStore()
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        // Two reads (failed open, then retry) see the same entry (I2 #3).
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertFalse(s.dropAccepted(7L, "room1", "req-other", "https://srv"))
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
    }

    @Test fun staleScopeHeadDoesNotBlockLaterMatch() {
        val s = AskAllPendingStore()
        // Head entry belongs to a moved destination; a later entry matches
        // the current identity and must still surface (GPT/Gem 08dde11).
        s.retainAccepted(7L, "room1", "req-stale", "https://old")
        s.retainAccepted(7L, "room1", "req-current", "https://srv")
        assertEquals("req-current", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        // FIFO within the matching scope: dropping the match reveals the next.
        s.retainAccepted(7L, "room1", "req-current-2", "https://srv")
        assertTrue(s.dropAccepted(7L, "room1", "req-current", "https://srv"))
        assertEquals("req-current-2", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
    }

    @Test fun dropRemovesOnlyOneOfDuplicateRequestIds() {
        val s = AskAllPendingStore()
        // Two asks can carry the same request_id (stub or server reuse):
        // dropping one must leave the other recoverable (GPT/Gem 08dde11).
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        assertTrue(s.dropAccepted(7L, "room1", "req-1", "https://srv"))
        assertEquals("req-1", s.peekAccepted(7L, "room1", "https://srv")?.requestId)
        assertTrue(s.dropAccepted(7L, "room1", "req-1", "https://srv"))
        assertNull(s.peekAccepted(7L, "room1", "https://srv"))
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

    @Test fun dropRemovesSelectedScopeEntryNotStaleSameIdHead() {
        val s = AskAllPendingStore()
        // Stale-scope head carries the SAME request_id as the current-scope
        // entry. peek selects the current one; drop must remove exactly that
        // identity and leave the stale entry untouched.
        s.retainAccepted(7L, "room1", "req-1", "https://old")
        s.retainAccepted(7L, "room1", "req-1", "https://srv")
        assertEquals("https://srv", s.peekAccepted(7L, "room1", "https://srv")?.serverUrl)
        assertTrue(s.dropAccepted(7L, "room1", "req-1", "https://srv"))
        assertNull(s.peekAccepted(7L, "room1", "https://srv"))
        assertEquals("https://old", s.peekAccepted(7L, "room1", "https://old")?.serverUrl)
        assertFalse(s.dropAccepted(7L, "room1", "req-1", "https://srv"))
        assertTrue(s.dropAccepted(7L, "room1", "req-1", "https://old"))
    }

    @Test fun claimLoserSeesBlockerAndOnlyOwnIdReclaims() {
        val s = AskAllPendingStore()
        // RoomScreen's claim-loser handler routes to the informed guard
        // (beginAskAll's blocked branch). That branch is taken iff
        // pendingClientId is non-null, so the loser must always see the
        // winner's id, and a different id must never re-claim.
        assertTrue(s.claim(7L, "room1", "winner"))
        assertFalse(s.claim(7L, "room1", "loser"))
        assertEquals("winner", s.pendingClientId(7L, "room1"))
        assertFalse(s.claim(7L, "room1", "loser"))
        assertTrue(s.claim(7L, "room1", "winner"))
        // Loser's override path: compare-remove of the blocker it was shown,
        // then the loser can claim.
        assertTrue(s.clearIfMatches(7L, "room1", "winner"))
        assertTrue(s.claim(7L, "room1", "loser"))
    }
}

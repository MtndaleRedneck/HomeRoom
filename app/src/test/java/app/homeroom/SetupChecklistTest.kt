package app.homeroom

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SetupChecklistTest {
    private suspend fun complete(c:SetupChecklist,allowed:Boolean=false) {
        c.notifications(allowed)
        c.connect(true,{accepted->accepted()},{},{},{})
    }
    @Test fun successRequiresActualRoomLoadAndAlertsAreOptional()=runBlocking {
        val c=SetupChecklist();c.notifications(false)
        c.connect(true,{accepted->
            assertEquals("Checking",c.checks[SetupStep.SERVER]?.state);assertFalse(c.ready)
            accepted()
            assertEquals("Passed",c.checks[SetupStep.SERVER]?.state)
            assertFalse(c.ready)
        },{fail("not a saved session")},{assertEquals("Checking",c.checks[SetupStep.ROOMS]?.state);assertFalse(c.ready)},{})
        assertTrue(c.ready);assertFalse(c.busy)
        for(step in SetupStep.entries.filter {it!=SetupStep.NOTIFICATIONS})assertEquals("Passed",c.checks[step]?.state)
        assertEquals("Waiting",c.checks[SetupStep.NOTIFICATIONS]?.state)
        c.notifications(true);assertEquals("Passed",c.checks[SetupStep.NOTIFICATIONS]?.state)
        assertTrue(c.checks[SetupStep.NOTIFICATIONS]!!.detail.contains("not been tested"))
    }
    @Test fun invalidLinkNeverSubmitsOrLoadsRooms()=runBlocking {
        val c=SetupChecklist()
        c.connect(false,{fail("submitted")},{fail("resumed")},{fail("rooms")},{})
        assertEquals("Needs attention",c.checks[SetupStep.LINK]?.state);assertFalse(c.submitted)
    }
    @Test fun lostEnrollmentResponseNeverResubmits()=runBlocking {
        val c=SetupChecklist();var submits=0
        repeat(2) {c.connect(true,{submits++;throw java.io.IOException()},{},{fail("rooms")},{})}
        assertEquals(1,submits);assertFalse(c.ready);assertFalse(c.busy)
        assertEquals("Not confirmed",c.checks[SetupStep.SIGN_IN]?.state)
        assertEquals("Not confirmed",c.checks[SetupStep.SERVER]?.state)
    }
    @Test fun bootstrapFailureRetriesOnlyReadsNotTheUsedLink()=runBlocking {
        val c=SetupChecklist();var submits=0;var reads=0
        c.connect(true,{accepted->submits++;accepted();throw java.io.IOException()},{},{fail("rooms")},{})
        assertTrue(c.accepted);assertFalse(c.ready)
        c.connect(false,{submits++},{reads++},{reads++},{})
        assertEquals(1,submits);assertEquals(2,reads);assertTrue(c.ready)
    }
    @Test fun roomFailurePreservesSignInAndRetryCannotEnroll()=runBlocking {
        val c=SetupChecklist()
        c.connect(true,{it()},{},{throw ApiException(503,"unavailable")},{})
        assertEquals("Passed",c.checks[SetupStep.SIGN_IN]?.state)
        assertEquals("Not confirmed",c.checks[SetupStep.ROOMS]?.state)
        c.connect(false,{fail("duplicate enroll")},{},{},{})
        assertTrue(c.ready)
    }
    @Test fun rejectionNeverClaimsRoomsLoadedOrSignInAccepted()=runBlocking {
        for(code in listOf("enrollment_expired","enrollment_used")) {
            val c=SetupChecklist()
            c.connect(true,{throw ApiException(410,code)},{},{fail("rooms")},{})
            assertEquals("Passed",c.checks[SetupStep.SERVER]?.state)
            assertEquals("Needs attention",c.checks[SetupStep.SIGN_IN]?.state)
            assertEquals("Waiting",c.checks[SetupStep.ROOMS]?.state)
            assertFalse(c.ready)
        }
    }
    @Test fun existingConnectionNeverNeedsLinkOrEnrollment()=runBlocking {
        val c=SetupChecklist(true);var read=0
        c.connect(false,{fail("enroll")},{read++},{read++},{})
        assertTrue(c.ready);assertEquals(2,read)
    }
    @Test fun expiredSavedSessionDoesNotClaimReady()=runBlocking {
        val c=SetupChecklist(true)
        c.connect(false,{fail("enroll")},{throw SessionEndedException("revoked")},{fail("rooms")},{})
        assertTrue(c.ended);assertFalse(c.ready)
    }
    @Test fun cancellationDoesNotBecomeFailureOrPermitResubmit()=runBlocking {
        val c=SetupChecklist()
        try {c.connect(true,{throw CancellationException()},{},{},{})}catch(_:CancellationException) {}
        assertFalse(c.busy);assertTrue(c.submitted)
        c.connect(true,{fail("resubmitted")},{},{},{})
        assertFalse(c.ready)
    }
    @Test fun concurrentTapAndCompletedTapDoNotDuplicateWork()=runBlocking {
        val c=SetupChecklist();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var submits=0
        val first=launch {c.connect(true,{accepted->submits++;started.complete(Unit);release.await();accepted()},{},{},{})}
        started.await();c.connect(true,{submits++},{},{},{})
        assertEquals(1,submits);release.complete(Unit);first.join()
        c.connect(true,{submits++},{},{},{})
        assertEquals(1,submits);assertTrue(c.ready)
    }
    @Test fun tokenDecodesOnceAndAmbiguityFailsClosed() {
        assertEquals("a%2Fb",SetupScreen.extractToken("https://fixture.example/homeroom/connect#token=a%252Fb"))
        for(link in listOf("https://fixture.example/#token=a&token=b","https://fixture.example/#token=a&connect=b","http://fixture.example/#token=a","https://user@fixture.example/#token=a","https://fixture.example:444/#token=a","https://fixture.example/#other=a","https://fixture.example/#token=a%0Ab"))assertNull(link,SetupScreen.parseLink(link))
        assertEquals("https://fixture.example" to "one-use",SetupScreen.parseLink("https://fixture.example/homeroom/connect#token=one-use"))
    }
    @Test fun repeatedRandomFailuresNeverProduceReadyOrDuplicateEnroll()=runBlocking {
        val random=java.util.Random(1511)
        repeat(300) {
            val c=SetupChecklist();var submits=0;val afterAccepted=random.nextBoolean()
            c.connect(true,{accepted->submits++;if(afterAccepted)accepted();throw java.io.IOException()},{},{fail("rooms")},{})
            assertFalse(c.ready)
            c.connect(true,{submits++;fail("second enrollment")},{throw java.io.IOException()},{fail("rooms")},{})
            assertEquals(1,submits);assertFalse(c.ready)
        }
    }

    @Test fun enroll401ClearsServerCheckingAndPreservesEndedSemantics()=runBlocking {
        // Regression (fp66c/Gemini): a 401 from the enroll request itself is a
        // plain SessionEndedException, not an ApiException, so the status
        // branch never ran and SERVER stayed "Checking" forever (pre-existing
        // 1.8 gap). Ended ordering must stay: SIGN_IN Needs attention, ROOMS
        // Waiting, no resubmit. Throw sites are ambiguous (mostly HTTP 401,
        // but ApiClient also throws locally on a blank saved cookie), so the
        // stuck SERVER becomes "Not confirmed" with non-network-blaming copy.
        val c=SetupChecklist();var submits=0
        c.connect(true,{submits++;throw SessionEndedException("owner_session_required")},{fail("resumed")},{fail("rooms")},{})
        assertTrue(c.ended);assertFalse(c.ready);assertFalse(c.busy)
        assertEquals("Not confirmed",c.checks[SetupStep.SERVER]?.state)
        assertTrue(c.checks[SetupStep.SERVER]!!.detail.contains("could not confirm the saved sign-in"))
        assertEquals("Needs attention",c.checks[SetupStep.SIGN_IN]?.state)
        assertEquals("Waiting",c.checks[SetupStep.ROOMS]?.state)
        c.connect(true,{submits++},{},{},{})
        assertEquals(1,submits)
        // Guard: a SERVER that already Passed (sign-in accepted, rooms load
        // then 401s) must survive the ended branch untouched.
        val c2=SetupChecklist()
        c2.connect(true,{it()},{},{throw SessionEndedException("owner_session_required")},{})
        assertTrue(c2.ended)
        assertEquals("Passed",c2.checks[SetupStep.SERVER]?.state)
        assertEquals("Needs attention",c2.checks[SetupStep.SIGN_IN]?.state)
        assertEquals("Waiting",c2.checks[SetupStep.ROOMS]?.state)
        // ApiException HTTP-response path: an actual HTTP answer (here 401)
        // takes the status branch, so SERVER is Passed with the existing
        // "server answered" copy; the ended condition needs api 401 AND an
        // accepted session, so a fresh-link 401 falls to the generic branch
        // and SIGN_IN is "Not confirmed", not ended.
        val c3=SetupChecklist();var submits3=0
        c3.connect(true,{submits3++;throw ApiException(401,"unauthorized")},{fail("resumed")},{fail("rooms")},{})
        assertFalse(c3.ended)
        assertEquals("Passed",c3.checks[SetupStep.SERVER]?.state)
        assertTrue(c3.checks[SetupStep.SERVER]!!.detail.contains("The server answered"))
        assertEquals("Not confirmed",c3.checks[SetupStep.SIGN_IN]?.state)
    }
}

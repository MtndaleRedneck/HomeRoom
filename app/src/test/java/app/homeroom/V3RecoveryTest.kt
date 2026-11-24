package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class V3RecoveryTest {
    private fun event(seq:Long,thread:String="main")=ChatEvent("event-$seq",seq,thread,null,"tab","Tab","Message $seq","2026-11-10T12:00:00Z",null)
    @Test fun seekUsesEventIdsUntilFirstUnreadIsLoaded()=runBlocking {
        val calls=mutableListOf<String>()
        val initial=TimelinePage(listOf(event(9),event(10)),"event-10","event-9",true)
        val result=seekFirstUnread(initial,4,{true}) {cursor ->
            calls.add(cursor)
            when(cursor) {
                "event-9" -> TimelinePage(listOf(event(6),event(7),event(8)),"event-8","event-6",true)
                "event-6" -> TimelinePage(listOf(event(3),event(4),event(5)),"event-5","event-3",true)
                else -> throw AssertionError("Wrong cursor")
            }
        }
        assertEquals(listOf("event-9","event-6"),calls)
        assertTrue(result.events.any {it.seq==4L});assertEquals("event-10",result.newest);assertEquals("event-3",result.oldest)
    }
    @Test fun noUnreadOrHiddenRoomDoesNotReadHistory()=runBlocking {
        val page=TimelinePage(listOf(event(9)),"event-9","event-9",true)
        for(unread in listOf(null,9L))assertEquals(page,seekFirstUnread(page,unread,{true}) {throw AssertionError("Unnecessary page")})
        assertEquals(page,seekFirstUnread(page,1,{false}) {throw AssertionError("Hidden room paged")})
    }
    @Test fun repeatedCursorFailsInsteadOfLooping()=runBlocking {
        val page=TimelinePage(listOf(event(9)),"event-9","event-9",true)
        try {seekFirstUnread(page,1,{true}) {page};fail("Repeated cursor accepted")}catch(e:ApiException) {assertEquals("invalid_response_cursor",e.code)}
    }
    @Test fun retryAfterRestartKeepsOriginalWireBody() {
        val first=OutgoingDraft.choose(null,"Hello",null,"main",listOf(event(4)))
        val restored=OutgoingDraft.from(first.json())
        val retry=OutgoingDraft.choose(restored,"Hello",null,"main",listOf(event(4),event(5)))
        assertEquals(first,retry);assertEquals("event-4",retry.parent)
        assertNotEquals(first.id,OutgoingDraft.choose(restored,"Changed",null,"main",listOf(event(5))).id)
    }
    @Test fun clearedSessionRejectsOldGenerationWithoutRestoringCookie() {
        val session=Session(RuntimeEnvironment.getApplication());session.cookie="fixture";session.serverUrl="https://example.com"
        val generation=session.generation
        session.clear()
        try {session.withinGeneration(generation) {session.cookie="stale"};fail("Stale session was accepted")}catch(_:kotlinx.coroutines.CancellationException) {}
        assertNull(session.cookie);assertEquals("https://example.com",session.serverUrl)
        session.withinGeneration(session.generation) {session.cookie="new"}
        assertEquals("new",session.cookie)
        session.clear()
    }
}
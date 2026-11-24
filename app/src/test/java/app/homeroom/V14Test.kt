package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import android.graphics.Typeface
import android.text.style.StyleSpan

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class V14Test {
    @Test fun markdownFormattingAndWebLinks() {
        val styled=Markdown.render("**Bold** and *italic* `code`\n- list\nhttps://example.com")
        assertEquals("Bold and italic code\n• list\nhttps://example.com",styled.toString())
        assertTrue(styled.getSpans(0,styled.length,StyleSpan::class.java).any {it.style==Typeface.BOLD})
        assertEquals(1,styled.getSpans(0,styled.length,android.text.style.URLSpan::class.java).size)
    }
    @Test fun markdownDoesNotRunHtml() {
        assertEquals("<script>alert(1)</script>",Markdown.render("<script>alert(1)</script>").toString())
    }
    @Test fun displayMentionUsesStableActor() {
        val members=listOf("ai_one" to "Tab 1","ai_two" to "Tab 10")
        val ranges=Mentions.ranges("Hi @Tab 10",members)
        assertEquals(listOf("ai_two"),ranges.map {it.second})
    }
    @Test fun duplicateMentionDoesNotGuess() {
        assertTrue(Mentions.ranges("@Twin",listOf("ai_1" to "Twin","ai_2" to "Twin")).isEmpty())
    }
    @Test fun deterministicActorColors() {
        assertEquals(personColor("ai_one"),personColor("ai_one"))
        assertNotEquals(C.muted,personColor("ai_one"))
    }
    @Test fun composerIsPerRoomAndClearsWithSession() {
        val ctx=org.robolectric.RuntimeEnvironment.getApplication()
        val s=Session(ctx);s.clear();s.saveComposerDraft("one","draft");s.saveComposerDraft("two","other")
        assertEquals("draft",s.composerDraft("one"));assertEquals("other",s.composerDraft("two"))
        s.clear();assertEquals("",s.composerDraft("one"))
    }
    @Test fun roomVersionsAndAttachments() {
        val room=Room.from(JSONObject("""{"room_id":"r","metadata_version":9,"archived":true,"members":[{"actor":"homeroom-test","display_name":"Test"},{"actor":"ai_real","display_name":"Real"}]}"""))
        assertEquals(9,room.metadataVersion);assertTrue(room.archived);assertEquals(listOf("ai_real"),room.members.map {it.first})
        val event=ChatEvent.from(JSONObject("""{"event_id":"e","attachments":[{"file_id":"f","name":"note.pdf","mime_type":"application/pdf","byte_size":3}]}"""))
        assertEquals("f",event.attachments.single().id)
    }
    @Test fun absentCapabilitiesDoNotGuessNewRoutes() {
        val s=Session(org.robolectric.RuntimeEnvironment.getApplication());s.clear()
        val api=ApiClient(s)
        assertFalse(api.supports("ai_modes"));assertFalse(api.supports("needs_you"))
    }
    @Test fun modeAndHealthParser() {
        val ai=OwnerAi.from(JSONObject("""{"actor":"ai_one","mode":"off","mode_version":3,"guide":{"stale":true},"pending_notice_count":2} """))
        assertEquals("off",ai.mode);assertEquals(3,ai.modeVersion);assertEquals(2,ai.pendingNoticeCount)
    }
    @Test fun phoneClock() {
        use24Hour=false;assertEquals("h:mm a",clockPattern());use24Hour=true;assertEquals("H:mm",clockPattern())
    }
}
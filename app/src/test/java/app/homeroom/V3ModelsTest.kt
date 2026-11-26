package app.homeroom

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class V3ModelsTest {
    @Test fun envelopeAndRoomFieldsStayIntact() {
        val page=RoomRoster.from(JSONObject("""{"server_time":"2026-11-10T15:00:00Z","global_pending_rules_count":2,"rooms":[{"room_id":"a","display_name":null,"pinned":true,"muted":true,"unread_count":4,"pending_rules_count":1,"waiting_count":3,"first_unread_seq":12,"waiting_cursor":"opaque","waiting_has_more":true,"waiting":[{"waiting_on":"tab","event_id":"e","since":"2026-11-10T14:48:00Z","notified_at":null,"excerpt":"Please check"}],"last_post":{"actor":"instinct","seq":15,"excerpt":"Result","own":false}}]}"""))
        val room=page.rooms.single()
        assertEquals("a",room.name); assertEquals(12L,room.firstUnreadSeq); assertTrue(room.pinned)
        assertEquals("opaque",room.waitingCursor); assertNull(room.waiting.single().notifiedAt)
        assertEquals(0,RoomSummary.unreadRooms(page.rooms));assertEquals(3,RoomSummary.pendingRules(page.rooms,page.globalPendingRulesCount));assertEquals(3,RoomSummary.waiting(page.rooms))
    }
    @Test fun ownAndStatusesAreServerFacts() {
        val event=ChatEvent.from(JSONObject("""{"actor":"someone","own":true,"mentions":["tab","instinct"],"assistant_statuses":[{"actor":"tab","reply_event_id":null,"notified_at":null},{"actor":"instinct","reply_event_id":"reply","notified_at":"time"}]}"""))
        assertTrue(event.own);assertEquals(2,event.mentions.size)
        assertFalse(event.assistantStatuses[0].replied);assertFalse(event.assistantStatuses[0].notified)
        assertTrue(event.assistantStatuses[1].replied);assertTrue(event.assistantStatuses[1].notified)
    }
    @Test fun filesUseByteSizeAndNullableMetadata() {
        val file=RoomFile.from(JSONObject("""{"file_id":"f","byte_size":2048,"size":9,"kind":"link","url":"https://example.com/file","title":null,"event_id":"e","mime_type":null}"""))
        assertEquals(2048L,file.size);assertEquals("link",file.kind);assertNull(file.title);assertEquals("e",file.eventId);assertEquals("",file.mimeType)
    }
    @Test fun rulesKeepActiveAndPendingVersionsAndUnknownIsNotActive() {
        val scope=RuleScope.from(JSONObject("""{"rules":[{"rule_id":"r","state":"active","version":1},{"rule_id":"r","state":"proposed","version":2}],"pending_count":1,"rereads":{"tab":null,"instinct":"time"}}"""))
        assertEquals(1,scope.active.single().version);assertEquals(2,scope.pending.single().version)
        assertEquals(mapOf("instinct" to "time"),scope.rereads);assertEquals("unknown",Rule.from(JSONObject("{}")).state)
    }
    @Test fun sessionIdentityComesFromBootstrap() {
        val device=OwnerDevice.from(JSONObject("""{"id":"public-id","label":"Phone","created":"date","last_used":null}"""))
        assertTrue(device.isCurrent("public-id"));assertFalse(device.isCurrent(null));assertNull(device.lastUsed)
    }
}
package app.homeroom

import android.content.Intent
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Notification-tap route contract (exact private12 decompile, MainActivity.J
// ROOM_ID block): tapping a room notification unwinds to the Chats tab root
// and opens the room on top of it, so Back returns to Chats - never to a
// stale room or page. An uncached room stays pending until the rooms cache
// delivers it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class NotificationRouteTest {
    private fun field(o:Any,n:String):java.lang.reflect.Field=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    private fun room(id:String,name:String)=Room.from(JSONObject("""{"room_id":"$id","display_name":"$name","members":"[]","main_thread_key":"main$id","unread_count":0,"pending_rules_count":0,"waiting_count":0,"muted":false,"waiting":[]}"""))
    private fun activity():MainActivity {
        val a=Robolectric.buildActivity(MainActivity::class.java).setup().get()
        field(a,"polling").setBoolean(a,false)
        a.session.ownerId="owner";a.session.ownerName="Owner";a.session.serverUrl="https://home-server.example";a.session.sessionId="phone"
        // enrolled requires serverUrl + cookie (Session.enrolled); without the
        // cookie the ROOM_ID gate drops the extra and no route is exercised.
        a.session.cookie="test-cookie"
        return a
    }
    private fun content(a:MainActivity)= (field(a,"content").get(a) as FrameLayout).getChildAt(0)

    @Test fun notificationRoomTapOpensRoomAndBackReturnsToChats() {
        val a=activity()
        val roomA=room("rA","Room A");val roomB=room("rB","Room B")
        a.cacheRooms(listOf(roomA,roomB))
        a.openRoom(roomA)
        assertTrue(content(a) is RoomScreen)
        a.onNewIntent(Intent(a,MainActivity::class.java).putExtra(HomeRoomMessagingService.EXTRA_ROOM_ID,"rB"))
        val shown=content(a)
        assertTrue(shown is RoomScreen)
        assertEquals("rB",(field(shown,"room").get(shown) as Room).id)
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(content(a) is RoomsScreen)
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(content(a) is RoomsScreen)
    }

    @Test fun notificationRoomPendingUntilCached() {
        val a=activity()
        val roomA=room("rA","Room A")
        a.cacheRooms(listOf(roomA))
        a.onNewIntent(Intent(a,MainActivity::class.java).putExtra(HomeRoomMessagingService.EXTRA_ROOM_ID,"rX"))
        assertTrue(content(a) is RoomsScreen)
        val roomX=room("rX","Room X")
        a.cacheRooms(listOf(roomA,roomX))
        val shown=content(a)
        assertTrue(shown is RoomScreen)
        assertEquals("rX",(field(shown,"room").get(shown) as Room).id)
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(content(a) is RoomsScreen)
    }
}

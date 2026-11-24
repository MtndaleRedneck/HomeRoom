package app.homeroom

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class V3BehaviorTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    @Test fun agesRequireServerClockAndNeverInventNotification() {
        assertNull(serverAge("2026-11-10T12:00:00Z",null))
        assertEquals("12m",serverAge("2026-11-10T12:00:00Z","2026-11-10T12:12:00Z"))
        assertEquals("2h",serverAge("2026-11-10T12:00:00Z","2026-11-10T14:30:00Z"))
        assertEquals("0m",serverAge("2026-11-10T14:00:00Z","2026-11-10T12:00:00Z"))
        val status=AssistantStatus.from(JSONObject("""{"actor":"tab","reply_event_id":null,"notified_at":null}"""))
        assertFalse(status.replied);assertFalse(status.notified)
    }
    @Test fun badgesCountRoomsNotPostsAndIncludeMutedWaiting() {
        val room=Room.from(JSONObject("""{"room_id":"a","unread_count":90,"pending_rules_count":2,"waiting_count":3}"""))
        val muted=room.copy(id="muted",muted=true)
        assertEquals(1,RoomSummary.unreadRooms(listOf(room,muted)))
        assertEquals(5,RoomSummary.pendingRules(listOf(room,muted),1))
        assertEquals(6,RoomSummary.waiting(listOf(room,muted)))
        assertEquals(listOf("muted","a"),RoomSummary.sorted(listOf(room.copy(lastSeq=10),muted.copy(pinned=true,lastSeq=1))).map {it.id})
    }
    @Test fun notificationActionsAreExplicitAndOnlyReplyMutable() {
        val context=RuntimeEnvironment.getApplication() as Context
        val builder=NotificationCompat.Builder(context,"test").setSmallIcon(R.drawable.ic_notification)
        NotificationActions.add(context,builder,"room-a","event-a")
        val actions=builder.build().actions
        assertEquals(listOf("Reply","Mark as read"),actions.map {it.title.toString()})
        assertEquals(1,actions[0].remoteInputs.size)
        assertFalse(actions[0].actionIntent.isImmutable)
        assertTrue(actions[1].actionIntent.isImmutable)
        assertEquals(NotificationActionReceiver::class.java.name,Shadows.shadowOf(actions[0].actionIntent).savedIntent.component?.className)
        assertEquals("event-a",Shadows.shadowOf(actions[0].actionIntent).savedIntent.getStringExtra("event"))
    }
    @Test fun backFromMutedReturnsToSettingsRoute() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        (field(a,"pollerJob").get(a) as? kotlinx.coroutines.Job)?.cancel()
        val settings=SettingsScreen(a)
        val content=field(a,"content").get(a) as android.widget.FrameLayout
        content.removeAllViews();content.addView(settings)
        a.openMutedRooms();a.onBackPressedDispatcher.onBackPressed()
        assertSame(settings,content.getChildAt(0))
        c.pause()
    }
    @Test fun falseOwnIsNotInferredFromActorString() {
        val event=ChatEvent.from(JSONObject("""{"actor":"owner","own":false}"""))
        assertFalse(event.own)
        assertTrue(ChatEvent.from(JSONObject("""{"actor":"other","own":true}""" )).own)
    }
}
package app.homeroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLayoutTest {
    private fun field(o:Any,n:String):Any = o.javaClass.getDeclaredField(n).apply { isAccessible=true }.get(o)
    private fun render(view:View,name:String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(396,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.EXACTLY))
        view.layout(0,0,396,760)
        val bitmap=Bitmap.createBitmap(396,760,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(C.bg); view.draw(canvas)
        File(System.getProperty("homeroom.previewDir", "build/native-previews")).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun malformedArraysAreNotEmptyLists() {
        assertEquals(0,JSONObject("{\"rooms\":[]}").requiredArray("rooms").length())
        for(value in listOf("{}","{\"rooms\":{}}","{\"rooms\":[null]}")) {
            try { JSONObject(value).requiredArray("rooms"); fail("Accepted malformed rooms") } catch(expected:ApiException) { assertTrue(expected.code.startsWith("invalid_response")) }
        }
    }
    @Test fun nullStringsStayAbsent() {
        val event=ChatEvent.from(JSONObject("""{"actor":"tab","client_id":null,"text":null,"created_at":null}"""))
        assertNull(event.clientId); assertEquals("",event.text); assertEquals("",event.createdAt)
        assertNull(JSONObject("""{"newest":null}""").nullableString("newest"))
    }
    @Test fun idleNavDoesNotCaptureAgain() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        (field(activity,"pollerJob") as kotlinx.coroutines.Job).cancel()
        val frame=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val nav=field(activity,"nav") as GlassNav
        nav.visibility=View.VISIBLE
        render(frame,"HomeRoom-r9-idle-nav")
        nav.backdropChanged()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        val count=nav.captureCount
        assertTrue(count>0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("Idle nav captured repeatedly",count,nav.captureCount)
        controller.pause()
    }
    @Test fun originalIconRendersWithoutRekeying() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        (field(activity,"pollerJob") as kotlinx.coroutines.Job).cancel()
        val drawable=activity.getDrawable(R.mipmap.ic_launcher)!!
        for((label,color) in listOf("dark" to C.bg,"light" to android.graphics.Color.WHITE)) {
            val bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); canvas.drawColor(color)
            drawable.setBounds(64,64,576,576); drawable.draw(canvas)
            File(System.getProperty("homeroom.previewDir","build/native-previews")).resolve("HomeRoom-r9-icon-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        controller.pause()
    }
    @Test fun roomAndMessageTextHaveRealWidth() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        (field(activity,"pollerJob") as kotlinx.coroutines.Job).cancel()
        val room=Room.from(JSONObject("""{"room_id":"demo","display_name":"HomeRoom test","unread_count":1,"members":[{"actor":"tab","display_name":"Tab"}],"last_post":{"text":"This room will stay available.","created_at":"2026-11-09T22:30:00Z"}}"""))
        val rooms=RoomsScreen(activity)
        @Suppress("UNCHECKED_CAST")
        (field(rooms,"rows") as MutableList<Room>).add(room)
        (field(rooms,"adapter") as RecyclerView.Adapter<*>).notifyDataSetChanged()
        val frame=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val content=field(activity,"content") as FrameLayout
        val header=field(activity,"header") as View
        header.visibility=View.VISIBLE
        (field(activity,"headerBack") as View).visibility=View.GONE
        (field(activity,"headerTitle") as TextView).text="HomeRoom"
        (field(activity,"nav") as View).visibility=View.VISIBLE
        content.removeAllViews(); content.addView(rooms,FrameLayout.LayoutParams(-1,-1))
        render(frame,"HomeRoom-r9-native-chats")
        fun textViews(v:View):List<TextView> = (if(v is TextView) listOf(v) else emptyList()) + (if(v is ViewGroup) (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) } else emptyList())
        val title=textViews(rooms).first { it.text.toString()=="HomeRoom test" }
        assertTrue("Room title collapsed to ${title.width}",title.width>120)
        val screen=RoomScreen(activity,room)
        @Suppress("UNCHECKED_CAST")
        (field(screen,"events") as MutableList<ChatEvent>).add(ChatEvent.from(JSONObject("""{"event_id":"demo-event","seq":1,"actor":"tab","text":"HomeRoom test: try now. This room will stay available.","created_at":"2026-11-09T22:30:00Z"}""")))
        @Suppress("UNCHECKED_CAST")
        (field(screen,"events") as MutableList<ChatEvent>).add(ChatEvent.from(JSONObject("""{"event_id":"short","seq":2,"actor":"tab","text":"ok","created_at":"2026-11-09T22:31:00Z"}""")))
        screen.javaClass.getDeclaredMethod("rebuild").apply { isAccessible=true }.invoke(screen)
        content.removeAllViews(); content.addView(screen,FrameLayout.LayoutParams(-1,-1))
        (field(activity,"headerBack") as View).visibility=View.VISIBLE
        (field(activity,"headerTitle") as TextView).apply { text="HomeRoom test"; textSize=17f }
        (field(activity,"headerSearch") as View).visibility=View.GONE
        val headerIcon=field(activity,"headerIcon") as FrameLayout
        headerIcon.visibility=View.VISIBLE; headerIcon.addView(roomIcon(activity,room,null,38),FrameLayout.LayoutParams(-1,-1))
        (field(activity,"nav") as View).visibility=View.GONE
        render(frame,"HomeRoom-r9-native-room")
        val body=textViews(screen).first { it.text.toString().startsWith("HomeRoom test: try now") }
        assertTrue("Message body collapsed to ${body.width}",body.width>150)
        assertEquals(C.text,body.currentTextColor)
        val short=textViews(screen).first { it.text.toString()=="ok" }
        assertTrue("Short bubble is fixed-width",(short.parent as View).width < (body.parent as View).width)
        controller.pause()
    }
}

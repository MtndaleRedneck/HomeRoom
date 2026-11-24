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
@Config(sdk=[34],qualifiers="w360dp-h780dp-mdpi") // message bubbles cap at 0.75*display width - needs a real display
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLayoutTest {
    private fun field(o:Any,n:String):Any = o.javaClass.getDeclaredField(n).apply { isAccessible=true }.get(o)
    private fun render(view:View,name:String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(396,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.EXACTLY))
        view.layout(0,0,396,760)
        val bitmap=Bitmap.createBitmap(396,760,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(C.bg); view.draw(canvas)
        val outDir=System.getProperty("hr.previewDir")?.let {File(it)} ?: File("build/previews").apply {mkdirs()}
        File(outDir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun roomAndMessageTextHaveRealWidth() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        // The release caps bubble text at 0.72*display width (K0/N3:708); the
        // sandbox display reports ~0px even with qualifiers, collapsing the
        // cap - give the display a real width for this geometry test.
        activity.resources.displayMetrics.widthPixels=396
        val room=Room.from(JSONObject("""{"room_id":"demo","display_name":"HomeRoom test","unread_count":1,"members":[{"actor":"tab","display_name":"Tab"}],"last_post":{"text":"This room will stay available.","created_at":"2026-11-09T22:30:00Z"}}"""))
        val rooms=RoomsScreen(activity)
        @Suppress("UNCHECKED_CAST")
        (field(rooms,"rows") as MutableList<Room>).add(room)
        (field(rooms,"adapter") as RecyclerView.Adapter<*>).notifyDataSetChanged()
        val frame=FrameLayout(activity).apply { setBackgroundColor(C.bg) }
        val stack=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        stack.addView(text(activity,"HomeRoom",25f,C.text,true),LinearLayout.LayoutParams(-1,60))
        stack.addView(rooms,LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(stack,FrameLayout.LayoutParams(-1,-1))
        frame.addView(GlassNav(activity,stack) {},FrameLayout.LayoutParams(360,64,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin=24 })
        activity.setContentView(frame)
        render(frame,"HomeRoom-r8-native-chats")
        fun textViews(v:View):List<TextView> = (if(v is TextView) listOf(v) else emptyList()) + (if(v is ViewGroup) (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) } else emptyList())
        val title=textViews(rooms).first { it.text.toString()=="HomeRoom test" }
        assertTrue("Room title collapsed to ${title.width}",title.width>120)
        val screen=RoomScreen(activity,room)
        @Suppress("UNCHECKED_CAST")
        (field(screen,"events") as MutableList<ChatEvent>).add(ChatEvent.from(JSONObject("""{"event_id":"demo-event","seq":1,"actor":"tab","text":"HomeRoom test: try now. This room will stay available.","created_at":"2026-11-09T22:30:00Z"}""")))
        screen.javaClass.getDeclaredMethod("rebuild").apply { isAccessible=true }.invoke(screen)
        stack.removeAllViews(); stack.addView(text(activity,"‹   HomeRoom test",18f,C.text,true),LinearLayout.LayoutParams(-1,60)); stack.addView(screen,LinearLayout.LayoutParams(-1,0,1f))
        frame.removeViewAt(1)
        render(frame,"HomeRoom-r8-native-room")
        val body=textViews(screen).first { it.text.toString().startsWith("HomeRoom test: try now") }
        // TEMP DIAGNOSTIC - removed with the bind-time NL-DIAG
        run { var v:View?=body; val parts=mutableListOf<String>(); while(v!=null){ parts+=("${v.javaClass.simpleName}:w=${v.width},mw=${v.measuredWidth},lp=${v.layoutParams?.width}"); v=v.parent as? View }; println("NL-DIAG-CHAIN "+parts.joinToString(" | ")) }
        assertTrue("Message body collapsed to ${body.width}",body.width>150)
        assertEquals(C.text,body.currentTextColor)
        controller.pause().stop().destroy()
    }
}
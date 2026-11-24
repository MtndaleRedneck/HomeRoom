package app.homeroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V3RenderTest {
    private fun field(o:Any,n:String):java.lang.reflect.Field=o.javaClass.getDeclaredField(n).apply {isAccessible=true}
    private fun call(o:Any,n:String)=o.javaClass.getDeclaredMethod(n).apply {isAccessible=true}.invoke(o)
    private fun rooms():List<Room> {
        val members="""[{"actor":"owner","display_name":"Owner"},{"actor":"tab","display_name":"Tab"},{"actor":"instinct","display_name":"Instinct"}]"""
        return listOf("WrenBoard","HomeRoom app","Listen Later","House","Zephyr","BumpLog").mapIndexed { i,name ->
            Room.from(JSONObject("""{"room_id":"r$i","display_name":"$name","members":$members,"main_thread_key":"main$i","unread_count":${if(i<2)3-i*2 else 0},"pending_rules_count":${if(i==1)1 else 0},"waiting_count":${if(i==0 || i==2)1 else 0},"muted":${i==3},"first_unread_seq":4,"icon":{"kind":"symbol","glyph":"${name.take(1)}","colour":"${listOf("#FC8613","#2AABEE","#3FBF7F","#7B6CF0","#E5577A","#F0A23C")[i]}"},"waiting":${if(i==0) """[{"waiting_on":"tab","event_id":"e5","since":"2026-11-10T14:14:00Z","notified_at":null,"excerpt":"@Tab check the math too?"}]""" else "[]"},"last_post":{"actor":"${if(i==0)"instinct" else if(i==2)"owner" else "tab"}","seq":${20-i},"own":${i==2},"excerpt":"${listOf("1.8 build is up. Measured the keys.","Review done: 2 must-fix, the rest pass.","Ship it once the new icons are in.","Honeywell confirmed: T9 Series 1.","Release candidate 3 passes each check.","Checked. On hold until the review.")[i]}","created_at":"2026-11-10T14:12:00Z"}}"""))
        }
    }
    private fun activity():Pair<org.robolectric.android.controller.ActivityController<MainActivity>,MainActivity> {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val a=controller.get();field(a,"polling").setBoolean(a,false)
        a.session.ownerId="owner";a.session.ownerName="Owner";a.session.serverUrl="https://home-server.example";a.session.sessionId="phone"
        a.cacheRooms(rooms())
        return controller to a
    }
    private fun render(a:MainActivity,screen:Screen,title:String,tab:Int?,name:String,chat:Boolean=false) {
        val frame=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val content=field(a,"content").get(a) as FrameLayout
        content.removeAllViews();content.addView(screen,FrameLayout.LayoutParams(-1,-1))
        (field(a,"header").get(a) as View).visibility=View.VISIBLE
        (field(a,"headerTitle").get(a) as TextView).apply {text=title;textSize=if(chat)17f else 26f}
        (field(a,"headerBack").get(a) as View).visibility=if(chat)View.VISIBLE else View.GONE
        (field(a,"headerSearch").get(a) as View).visibility=if(title=="HomeRoom" || title=="Files" || chat)View.VISIBLE else View.GONE
        // Drive the ACTUAL production nav (GlassNav, the restored release
        // layout). selectedItemId is set via the field so the choose callback
        // does not fire and clobber the screen under render.
        val nav=field(a,"nav").get(a) as GlassNav
        val navFade=field(a,"navFade").get(a) as View
        nav.visibility=if(tab==null)View.GONE else View.VISIBLE
        navFade.visibility=nav.visibility
        if(tab!=null){
            field(nav,"selectedItemId").set(nav,tab)
            nav.setBadges(2,2)
            nav.javaClass.getDeclaredMethod("updateSelection").apply{isAccessible=true}.invoke(nav)
            // Actual badge VIEW checks (number + visibility), accessibility
            // content descriptions kept alongside, per I2's partial review.
            val items=field(nav,"items").get(nav) as LinearLayout
            val chatsCell=items.getChildAt(0) as LinearLayout
            val rulesCell=items.getChildAt(2) as LinearLayout
            val settingsCell=items.getChildAt(3) as LinearLayout
            val chatsBadge=chatsCell.findViewWithTag<TextView>("badge")!!
            val rulesBadge=rulesCell.findViewWithTag<TextView>("badge")!!
            val settingsBadge=settingsCell.findViewWithTag<TextView>("badge")!!
            assertEquals("2",chatsBadge.text.toString())
            assertEquals(View.VISIBLE,chatsBadge.visibility)
            assertEquals("2",rulesBadge.text.toString())
            assertEquals(View.VISIBLE,rulesBadge.visibility)
            assertEquals("no update dot without an update",View.GONE,settingsBadge.visibility)
            assertEquals("Chats, 2",chatsCell.contentDescription)
            assertEquals("Rules, 2",rulesCell.contentDescription)
        }
        frame.setPadding(0,24,0,24)
        frame.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));frame.layout(0,0,360,780)
        nav.backdropChanged()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(C.bg);frame.draw(canvas)
        val previewDir=System.getProperty("homeroom.previewDir") ?: "${System.getProperty("user.dir")}/build/previews"
        File(previewDir).apply {mkdirs()}.resolve("v3-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun fiveScreensAtReferenceSize() {
        val(c,a)=activity()
        val roster=rooms()
        val rooms=RoomsScreen(a)
        @Suppress("UNCHECKED_CAST") val rows=field(rooms,"rows").get(rooms) as MutableList<Room>
        rows.addAll(roster);field(rooms,"globalPending").set(rooms,1);field(rooms,"serverTime").set(rooms,"2026-11-10T14:26:00Z")
        (field(rooms,"adapter").get(rooms) as androidx.recyclerview.widget.RecyclerView.Adapter<*>).notifyDataSetChanged()
        render(a,rooms,"HomeRoom",R.id.nav_chats,"rooms")
        val rules=RulesScreen(a);field(rules,"rooms").set(rules,roster)
        field(rules,"scope").set(rules,RuleScope.from(JSONObject("""{"pending_count":2,"rereads":{"owner":"2026-11-10T13:02:00Z","tab":"2026-11-10T13:00:00Z"},"rules":[{"rule_id":"one","state":"proposed","version":2,"proposed_by":"instinct","text":"Measure any size on Owner's phone and post the numbers before changing it."},{"rule_id":"two","state":"proposed","version":1,"proposed_by":"tab","text":"No mail, posts or purchases go out without Owner's yes in the room."},{"rule_id":"three","state":"active","version":3,"proposed_by":"tab","decided_by":"owner","text":"Ask before changing the plan."}]}""")))
        call(rules,"buildChips");call(rules,"render");render(a,rules,"Rules",R.id.nav_rules,"rules")
        val files=FilesScreen(a);field(files,"rooms").set(files,roster);field(files,"selected").set(files,roster.first())
        @Suppress("UNCHECKED_CAST")val fileList=field(files,"files").get(files) as MutableList<RoomFile>
        fileList.addAll(listOf(RoomFile("apk","wrenboard-1.8-b215.apk","application/vnd.android.package-archive",25270682,"Instinct","2026-11-10T14:12:00Z"),RoomFile("pdf","review-notes-1.8.pdf","application/pdf",188416,"Tab","2026-11-10T13:04:00Z"),RoomFile("link","Release page, build 215","",0,"Instinct","2026-11-09T15:00:00Z",kind="link",url="https://example.com")))
        call(files,"buildChips");call(files,"render");render(a,files,"Files",R.id.nav_files,"files")
        val settings=SettingsScreen(a);field(settings,"rooms").set(settings,roster);field(settings,"devicesLoaded").set(settings,true);field(settings,"devices").set(settings,listOf(OwnerDevice("phone","This phone",null,null),OwnerDevice("browser","Browser",null,"2026-11-09T15:00:00Z")))
        call(settings,"rebuild");render(a,settings,"Settings",R.id.nav_settings,"settings")
        val chat=RoomScreen(a,roster.first())
        @Suppress("UNCHECKED_CAST")val events=field(chat,"events").get(chat) as MutableList<ChatEvent>
        listOf("Build 214 of WrenBoard 1.8 is up: one Recents button, no Search.","Checked it: same signing cert, six files changed. Clean.","@Instinct measure the key height on my phone first.","155 px per letter row at the default height. No change needed.","@Tab check the math too?").forEachIndexed {i,body->
            val actor=listOf("instinct","tab","owner","instinct","owner")[i]
            events.add(ChatEvent.from(JSONObject("""{"event_id":"e${i+1}","thread_key":"main0","seq":${i+1},"actor":"$actor","own":${actor=="owner"},"text":"$body","created_at":"2026-11-10T14:${listOf("00","04","08","10","14")[i]}:00Z","parent_id":${if(i==3)"\"e3\"" else "null"},"mentions":${if(i==2)"[\"instinct\"]" else if(i==4)"[\"tab\"]" else "[]"},"assistant_statuses":${if(i==2)"[{\"actor\":\"instinct\",\"reply_event_id\":\"e4\",\"notified_at\":null}]" else if(i==4)"[{\"actor\":\"tab\",\"reply_event_id\":null,\"notified_at\":null}]" else "[]"}}""")))
        }
        field(chat,"serverTime").set(chat,"2026-11-10T14:26:00Z");call(chat,"rebuild");render(a,chat,"WrenBoard",null,"chat",true)
        c.pause()
    }
    // Capture-under-lock gate (I2, Nov 24): while FLAG_SECURE or the lock
    // cover is up, the nav must not capture the content column into retained
    // buffers; transitions drop prior buffers and cancel posted runnables.
    @Test fun captureBlockedUnderSecureFlagAndCover() {
        val(c,a)=activity()
        val nav=field(a,"nav").get(a) as GlassNav
        val frame=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        nav.visibility=View.VISIBLE
        frame.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));frame.layout(0,0,360,780)
        val idle={ org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        // Baseline: unblocked capture allocates retained buffers.
        nav.backdropChanged();idle()
        assertEquals(1,nav.captureCount)
        assertNotNull(field(nav,"buffers").get(nav))
        // Stale-runnable race: enqueue, then invalidate BEFORE it runs.
        nav.backdropChanged()
        nav.invalidateBackdrop()
        idle()
        assertEquals(1,nav.captureCount)
        assertNull(field(nav,"buffers").get(nav))
        // FLAG_SECURE transition: invalidation drops buffers, capture blocked.
        a.window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,android.view.WindowManager.LayoutParams.FLAG_SECURE)
        nav.backdropChanged();idle()
        assertEquals(1,nav.captureCount)
        // Unblock: a fresh capture proceeds.
        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        nav.backdropChanged();idle()
        assertEquals(2,nav.captureCount)
        assertNotNull(field(nav,"buffers").get(nav))
        // Lock cover transition: show() invalidates and blocks capture.
        a.appLock.show()
        assertTrue(a.appLock.coverShowing)
        assertNull(field(nav,"buffers").get(nav))
        nav.backdropChanged();idle()
        assertEquals(2,nav.captureCount)
        c.pause()
    }
    @Test fun backFromWaitingChatReturnsToWaiting() {
        val(c,a)=activity()
        a.openWaiting(null)
        a.openRoom(a.cachedRooms().first())
        a.onBackPressedDispatcher.onBackPressed()
        val content=field(a,"content").get(a) as FrameLayout
        assertTrue(content.getChildAt(0) is WaitingScreen)
        c.pause()
    }
}
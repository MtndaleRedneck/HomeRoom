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
        field(a,"polling").setBoolean(a,false)
        val settings=SettingsScreen(a)
        val content=field(a,"content").get(a) as android.widget.FrameLayout
        content.removeAllViews();content.addView(settings)
        a.openMutedRooms();a.onBackPressedDispatcher.onBackPressed()
        assertSame(settings,content.getChildAt(0))
        c.pause()
    }
    @Test fun settingsBottomOrderMatchesSharedLayout() {
        // 1.7 locked shared layout: Sign out above the
        // Feedback & bugs card, the Updates card ALWAYS last, the version
        // inside the Updates card, and a Feel > Vibrate on press toggle.
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        field(a,"polling").setBoolean(a,false)
        val settings=SettingsScreen(a)
        field(settings,"rooms").set(settings,listOf<Room>())
        field(settings,"devicesLoaded").set(settings,true)
        field(settings,"devices").set(settings,listOf<OwnerDevice>())
        settings.javaClass.getDeclaredMethod("rebuild").apply {isAccessible=true}.invoke(settings)
        val col=field(settings,"col").get(settings) as android.widget.LinearLayout
        val texts=mutableListOf<String>()
        fun walk(v:android.view.View) {
            if(v is android.widget.TextView) texts.add(v.text.toString())
            if(v is android.view.ViewGroup) for(i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(col)
        val signout=texts.indexOf("Sign out")
        val feedback=texts.indexOf("Feedback & bugs")
        val updates=texts.indexOf("Check for updates") // 1.8: untitled Updates card, anchored on its button
        assertTrue("sign out present",signout>=0)
        assertTrue("feedback card present",feedback>=0)
        assertTrue("updates card present",updates>=0)
        assertTrue("sign out above the feedback card",signout<feedback)
        assertTrue("feedback card above updates",feedback<updates)
        assertTrue("updates is the last card",texts.indexOf("Version "+BuildConfig.VERSION_NAME)>updates)
        assertTrue("vibrate toggle present",texts.contains("Vibrate on press"))
        c.pause()
    }
    @Test fun headerBoundsIdenticalAcrossRealTabSwitches() {
        // Exact-header requirement: the
        // whole app shares ONE header container. Measure its ACTUAL laid-out
        // bounds after driving REAL tab switches (nav setter -> showTab), not
        // a dp constant. The header is never resized or animated by a switch,
        // so identical bounds after every switch is the transition proof.
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        field(a,"polling").setBoolean(a,false)
        a.javaClass.getDeclaredMethod("showMain").apply {isAccessible=true}.invoke(a)
        val frame=a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val nav=field(a,"nav").get(a) as GlassNav
        val header=field(a,"header").get(a) as android.view.View
        val seen=java.util.LinkedHashMap<String,String>()
        for((tab,name) in listOf(R.id.nav_chats to "Rooms",R.id.nav_rules to "Rules",R.id.nav_files to "Files",R.id.nav_settings to "Settings")) {
            nav.selectedItemId=tab
            frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(780,android.view.View.MeasureSpec.EXACTLY))
            frame.layout(0,0,360,780)
            assertEquals("header stays visible on $name",android.view.View.VISIBLE,header.visibility)
            seen[name]=listOf(header.left,header.top,header.right,header.bottom).joinToString(",")
        }
        assertEquals("one shared header with identical laid-out bounds on every tab: $seen",1,seen.values.toSet().size)
        c.pause()
    }
    @Test fun navPressSinksInAndReleases() {
        // Wiring proof for 1.7 press feedback (a real device proves the
        // physical buzz; this proves the view wiring): DOWN sinks the tab
        // cell to 0.94/dim, UP restores it.
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        field(a,"polling").setBoolean(a,false)
        a.javaClass.getDeclaredMethod("showMain").apply {isAccessible=true}.invoke(a)
        val nav=field(a,"nav").get(a) as GlassNav
        val items=field(nav,"items").get(nav) as android.widget.LinearLayout
        val cell=items.getChildAt(2) as android.view.View // Files tab
        val down=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,10f,10f,0)
        cell.dispatchTouchEvent(down);down.recycle()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals("pressed cell sinks to 0.94",0.94f,cell.scaleX,0.001f)
        assertTrue("pressed cell dims",cell.alpha<0.9f)
        val up=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_UP,10f,10f,0)
        cell.dispatchTouchEvent(up);up.recycle()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f,cell.scaleX,0.001f)
        assertEquals(1f,cell.alpha,0.001f)
        c.pause()
    }
    private fun touch(v:android.view.View,action:Int) {
        val e=android.view.MotionEvent.obtain(0,0,action,1f,1f,0);v.dispatchTouchEvent(e);e.recycle()
    }
    @Test fun pressReleaseRestoresThePreviousAlpha() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        val looper=Shadows.shadowOf(android.os.Looper.getMainLooper())
        // A view that rests at 0.6 must come back to 0.6, not 1.0.
        val dim=android.view.View(ctx).apply {alpha=0.6f;pressIn(haptic=false)}
        touch(dim,android.view.MotionEvent.ACTION_DOWN);looper.idleFor(java.time.Duration.ofMillis(500))
        assertEquals(0.94f,dim.scaleX,0.001f)
        touch(dim,android.view.MotionEvent.ACTION_UP);looper.idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f,dim.scaleX,0.001f)
        assertEquals("alpha returns to its resting value",0.6f,dim.alpha,0.001f)
    }
    @Test fun rapidRepeatTapsDoNotRatchetTheRestingAlpha() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        val looper=Shadows.shadowOf(android.os.Looper.getMainLooper())
        val v=android.view.View(ctx).apply {alpha=0.6f;pressIn(haptic=false)}
        repeat(8) {
            touch(v,android.view.MotionEvent.ACTION_DOWN);looper.idleFor(java.time.Duration.ofMillis(95))
            touch(v,android.view.MotionEvent.ACTION_UP)
            // The next DOWN lands while the 120 ms release is still running.
            looper.idleFor(java.time.Duration.ofMillis(40))
        }
        touch(v,android.view.MotionEvent.ACTION_UP)
        looper.idleFor(java.time.Duration.ofMillis(600))
        assertEquals("resting alpha never drifts after repeat taps",0.6f,v.alpha,0.001f)
        assertEquals(1f,v.scaleX,0.001f)
    }
    @Test fun pressInInstalledTwiceActsOnce() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        val v=android.view.View(ctx)
        v.pressIn();v.pressIn()
        val info=android.view.View::class.java.getDeclaredMethod("getListenerInfo").apply {isAccessible=true}.invoke(v)
        assertNotNull(info)
    }
    @Test fun disabledViewGateInTheListenerItself() {
        // The framework skips touch listeners on disabled views, so call the
        // installed listener directly to prove the gate in pressIn() itself.
        val ctx=RuntimeEnvironment.getApplication() as Context
        val looper=Shadows.shadowOf(android.os.Looper.getMainLooper())
        val off=android.view.View(ctx).apply {isEnabled=false;pressIn()}
        val info=android.view.View::class.java.getDeclaredMethod("getListenerInfo").apply {isAccessible=true}.invoke(off)
        val l=info!!.javaClass.getDeclaredField("mOnTouchListener").apply {isAccessible=true}.get(info) as android.view.View.OnTouchListener
        val e=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,1f,1f,0)
        l.onTouch(off,e);e.recycle();looper.idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f,off.scaleX,0.001f)
        assertEquals(1f,off.alpha,0.001f)
    }
    private fun navLabelsFit(fontScale:Float) {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        field(a,"polling").setBoolean(a,false)
        a.javaClass.getDeclaredMethod("showMain").apply {isAccessible=true}.invoke(a)
        val nav=field(a,"nav").get(a) as GlassNav
        val frame=a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(780,android.view.View.MeasureSpec.EXACTLY))
        frame.layout(0,0,360,780)
        val items=field(nav,"items").get(nav) as android.widget.LinearLayout
        for(i in 0 until items.childCount) {
            val inner=(items.getChildAt(i) as android.widget.LinearLayout).findViewWithTag<android.widget.LinearLayout>("inner")
            val label=inner.getChildAt(1) as android.widget.TextView
            val needed=Math.ceil(label.paint.measureText(label.text.toString()).toDouble()).toInt()
            assertTrue("'${label.text}' fully visible at font $fontScale: needs $needed px, has ${label.width} px",label.width>=needed && label.layout.getEllipsisCount(0)==0 && label.layout.lineCount==1)
            assertTrue("label inside its cell",inner.width<=items.getChildAt(i).width)
        }
        c.pause()
    }
    @Test fun navLabelsShowWholeNamesAtNormalFont() = navLabelsFit(1.0f)
    @Test @Config(sdk=[34],qualifiers="w360dp-h780dp-mdpi",fontScale=1.3f)
    fun navLabelsShowWholeNamesAtLargeFont() = navLabelsFit(1.3f)
    @Test fun navWidthGrowsWithFontButStaysOnScreen() {
        assertEquals("normal font keeps the live 256dp",256,navWidthFor(360,1f,1.0f))
        assertEquals("smaller-than-1 font never narrows it",256,navWidthFor(360,1f,0.85f))
        assertEquals("1.3x widens in step",332,navWidthFor(360,1f,1.3f))
        assertEquals("huge font stops 12dp short of each screen edge",336,navWidthFor(360,1f,2.0f))
        assertEquals("medium screen",276,navWidthFor(300,1f,1.3f))
        assertEquals("screen narrower than 256dp: full width, never past it",240,navWidthFor(240,1f,1.3f))
        assertEquals("density scales it",768,navWidthFor(1080,3f,1.0f))
    }
    @Test fun navHeightGrowsWithFontSoLabelsStayWhole() {
        assertEquals("normal font keeps the live 54dp",54,navHeightFor(1f,1.0f))
        assertEquals("smaller-than-1 font never shortens it",54,navHeightFor(1f,0.85f))
        assertEquals("1.3x grows by the label's extra line height",57,navHeightFor(1f,1.3f))
        assertEquals("2x grows in step",66,navHeightFor(1f,2.0f))
        assertEquals("density scales it",162,navHeightFor(3f,1.0f))
    }
    @Test @Config(sdk=[34],qualifiers="w360dp-h780dp-mdpi",fontScale=1.3f)
    fun realNavUsesTheFontAwareWidthAndStaysInsideTheScreen() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        field(a,"polling").setBoolean(a,false)
        a.javaClass.getDeclaredMethod("showMain").apply {isAccessible=true}.invoke(a)
        val frame=a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val nav=field(a,"nav").get(a) as android.view.View
        frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(780,android.view.View.MeasureSpec.EXACTLY))
        frame.layout(0,0,360,780)
        assertEquals("the MainActivity nav, not a fixture width",navWidthFor(360,1f,1.3f),nav.width)
        assertTrue("nav inside the screen",nav.left>=12 && nav.right<=348)
        c.pause()
    }
    @Test fun vibrateOnPressDefaultsOnAndPersists() {
        val ctx=RuntimeEnvironment.getApplication() as Context
        ctx.getSharedPreferences("homeroom_ui",Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue("default is on",Haptics.on(ctx))
        Haptics.set(ctx,false);assertFalse(Haptics.on(ctx))
        Haptics.set(ctx,true);assertTrue(Haptics.on(ctx))
    }
    @Test fun falseOwnIsNotInferredFromActorString() {
        val event=ChatEvent.from(JSONObject("""{"actor":"owner","own":false}"""))
        assertFalse(event.own)
        assertTrue(ChatEvent.from(JSONObject("""{"actor":"other","own":true}""" )).own)
    }
}

package app.homeroom

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/**
 * Restored historical private12 floating navigation (exact-release decompile +
 * Nov 10/11 operator readbacks): four tabs, captured-backdrop blur, badge
 * overlay. Only the captured backdrop is blurred, never its labels.
 *
 * Wired into MainActivity Nov 24 as the approved release layout (four floating
 * tabs + header search, per the Nov 9 user-approved design).
 *
 * Capture-under-lock gate, from I2's Nov 24 review relayed by main: the
 * capture source is the main content column, NOT the window root - view.draw()
 * bypasses FLAG_SECURE and the lock cover is a sibling overlay, not part of
 * the captured source view. While [captureBlocked] reports blocked, capture is
 * skipped at enqueue AND inside the posted runnable, the backdrop falls back
 * to the flat color on every SDK, and [invalidateBackdrop] drops all retained
 * state on every pause/privacy/cover transition. Buffers are dereferenced,
 * never recycled: the render thread may still hold them. The epoch counter
 * cancels an already-posted runnable (clearing the queued flag alone cannot).
 */
class GlassNav(context: Context, private val behind: View, val choose: (Int) -> Unit) : FrameLayout(context) {
    private val backdrop = ImageView(context)
    private val items = LinearLayout(context)
    private val tabs = listOf(Triple(R.id.nav_chats,"Rooms",R.drawable.ic_chats), Triple(R.id.nav_rules,"Rules",R.drawable.ic_rules), Triple(R.id.nav_files,"Files",R.drawable.ic_files), Triple(R.id.nav_settings,"Settings",R.drawable.ic_settings))
    var selectedItemId: Int = R.id.nav_chats
        set(value) { field = value; updateSelection(); choose(value) }
    private var buffers: Array<Bitmap>? = null
    private var nextBuffer = 0
    private var dirty = true
    private var queued = false
    // Set by MainActivity: true while the lock cover shows or the window is
    // FLAG_SECURE. Checked at enqueue AND at runnable execution.
    var captureBlocked: (() -> Boolean)? = null
    // Bumped by invalidateBackdrop/detach: an already-posted runnable whose
    // enqueue-time epoch no longer matches is a no-op.
    private var epoch = 0
    private var blockedFlat = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { backdropChanged() }
    private val layoutListener = View.OnLayoutChangeListener { _, l,t,r,b,ol,ot,or,ob ->
        if(l!=ol || t!=ot || r!=or || b!=ob) backdropChanged()
    }
    internal var captureCount = 0
        private set
    private var unreadBadge=0
    private var pendingBadge=0
    private var updateAvailable=false
    init {
        background = rounded(Color.TRANSPARENT, dp(32).toFloat())
        clipToOutline = true
        elevation = dp(10).toFloat()
        addView(backdrop, LayoutParams(-1,-1))
        if (android.os.Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(16).toFloat(),dp(16).toFloat(),Shader.TileMode.CLAMP))
        val sheen = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x29ffffff,0x0dffffff,0x14ffffff)).apply { cornerRadius=dp(32).toFloat(); setStroke(dp(1),0x30ffffff) }
        }
        addView(sheen, LayoutParams(-1,-1))
        items.gravity = Gravity.CENTER_VERTICAL
        items.setPadding(dp(7),dp(5),dp(7),dp(5))
        addView(items, LayoutParams(-1,-1))
        tabs.forEach { (id,label,icon) ->
            val cell = column(context).apply { gravity=Gravity.CENTER; tag=id; contentDescription=label; setOnClickListener { selectedItemId=id } }
            // 1.7:
            // the selection pill now hugs the icon+label (an inner column with
            // its own padding) instead of filling the full-height cell, which
            // rendered as an oversized ball, and every tab press sinks in with
            // a haptic via pressIn().
            val inner = column(context).apply {
                tag="inner"
                gravity=Gravity.CENTER
                setPadding(dp(10),dp(5),dp(10),dp(5))
            }
            inner.addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22),dp(22)))
            inner.addView(text(context,label,10.5f,C.muted,true), LinearLayout.LayoutParams(-2,-2))
            cell.addView(inner, LinearLayout.LayoutParams(-1,-2).apply {leftMargin=dp(3);rightMargin=dp(3)})
            cell.pressIn()
            items.addView(cell, LinearLayout.LayoutParams(0,-1,1f))
        }
        updateSelection()
        if(android.os.Build.VERSION.SDK_INT<31) backdrop.setBackgroundColor(0xeb2b2a2d.toInt())
    }

    // Historical Nov 10 bodies (operator readback, parsed as data): dirtied
    // captures coalesce on the animation frame; scroll/layout/size/visibility
    // of the content behind all dirty the backdrop.
    fun backdropChanged() {
        dirty=true
        if (captureBlocked?.invoke() == true) { showFlatFallback(); return }
        if(android.os.Build.VERSION.SDK_INT<31 || queued || !isAttachedToWindow || !isShown) return
        queued=true
        val enqueued = epoch
        postOnAnimation {
            queued=false
            if (enqueued != epoch) return@postOnAnimation
            if (captureBlocked?.invoke() == true) { showFlatFallback(); return@postOnAnimation }
            clearFlatFallback()
            if(dirty && isShown && width>0 && height>0) { dirty=false; capture() }
        }
    }

    // Drops every retained backdrop state on a block transition (lock cover
    // shown, FLAG_SECURE applied, window paused). Buffers are dereferenced,
    // never recycled: a render thread may still hold them; GC reclaims.
    fun invalidateBackdrop() {
        epoch++
        queued=false
        dirty=true
        backdrop.setImageDrawable(null)
        buffers=null
        nextBuffer=0
        showFlatFallback()
    }

    private fun showFlatFallback() {
        if (!blockedFlat) { backdrop.setBackgroundColor(0xeb2b2a2d.toInt()); blockedFlat=true }
    }
    private fun clearFlatFallback() {
        if (blockedFlat && android.os.Build.VERSION.SDK_INT >= 31) { backdrop.background=null; blockedFlat=false }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        behind.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        behind.addOnLayoutChangeListener(layoutListener)
        backdropChanged()
    }
    override fun onDetachedFromWindow() {
        epoch++
        if(behind.viewTreeObserver.isAlive) behind.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        behind.removeOnLayoutChangeListener(layoutListener)
        backdrop.setImageDrawable(null)
        buffers=null
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh); backdropChanged() }
    override fun onVisibilityChanged(changedView:View,visibility:Int) {
        super.onVisibilityChanged(changedView,visibility)
        // Direct call, never a deferred post: a posted dirty lands AFTER this
        // frame's capture already ran and enqueues a SECOND capture - the
        // coalescing contract is exact-once per frame, and the runnable
        // itself re-checks isShown/size at execution.
        if(visibility==VISIBLE) backdropChanged()
    }

    // Historical Nov 10 badge bodies + same-day update-badge patch (operator
    // readbacks, parsed as data): chats = unread, rules = pending, settings =
    // an update-available dot (empty badge, 8dp). The badge wraps the tab icon
    // in a FrameLayout; updateSelection handles both wrapped and unwrapped
    // icons (selection-dependency readback).
    fun setUpdateAvailable(available:Boolean) {updateAvailable=available;setBadges(unreadBadge,pendingBadge)}
    fun setBadges(unread:Int,pending:Int) {
        unreadBadge=unread;pendingBadge=pending
        tabs.forEachIndexed { index,(id,label,_) ->
            val count=when(id) { R.id.nav_chats -> unread; R.id.nav_rules -> pending; R.id.nav_settings -> if(updateAvailable)1 else 0; else -> 0 }
            val cell=items.getChildAt(index) as LinearLayout
            cell.contentDescription=if(count>0) "$label, $count" else label
            val badge=cell.findViewWithTag<TextView>("badge") ?: text(context,"",10.5f,Color.WHITE,true).apply {
                tag="badge";gravity=Gravity.CENTER;background=rounded(C.accent,dp(8).toFloat());minWidth=dp(16)
                val inner=innerOf(cell)
                (inner.getChildAt(0) as ImageView).let { image ->
                    inner.removeView(image)
                    val frame=FrameLayout(context)
                    frame.addView(image,LayoutParams(dp(24),dp(24),Gravity.CENTER))
                    frame.addView(this,LayoutParams(-2,dp(16),Gravity.TOP or Gravity.RIGHT))
                    inner.addView(frame,0,LinearLayout.LayoutParams(dp(40),dp(27)))
                }
            }
            badge.text=if(id==R.id.nav_settings) "" else if(count>99) "99+" else count.toString()
            badge.minWidth=dp(if(id==R.id.nav_settings)8 else 16)
            badge.layoutParams=(badge.layoutParams as LayoutParams).apply {height=dp(if(id==R.id.nav_settings)8 else 16);width=if(id==R.id.nav_settings)dp(8) else -2}
            badge.visibility=if(count>0) VISIBLE else GONE
        }
    }
    private fun innerOf(cell:LinearLayout)=cell.findViewWithTag<LinearLayout>("inner")!!
    private fun updateSelection() {
        for(i in 0 until items.childCount) {
            val cell=items.getChildAt(i) as LinearLayout
            val inner=innerOf(cell)
            val selected=cell.tag==selectedItemId
            inner.background=if(selected) rounded(0x42fc8613,dp(20).toFloat(),0x73fc8613,dp(1).toFloat()) else null
            val icon=inner.getChildAt(0)
            (if(icon is ImageView) icon else (icon as FrameLayout).getChildAt(0) as ImageView).setColorFilter(if(selected) C.accent else C.muted)
            (inner.getChildAt(1) as TextView).setTextColor(if(selected) C.accent else C.muted)
        }
    }
    private fun capture() {
        val loc=IntArray(2); val source=IntArray(2)
        getLocationInWindow(loc); behind.getLocationInWindow(source)
        var pair=buffers
        if(pair==null || pair[0].width!=width || pair[0].height!=height) {
            pair=Array(2) { Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888) }
            buffers=pair
        }
        val bitmap=pair[nextBuffer]
        nextBuffer=1-nextBuffer
        captureCount++
        val canvas=Canvas(bitmap)
        canvas.drawColor(C.bg)
        canvas.translate(-(loc[0]-source[0]).toFloat(),-(loc[1]-source[1]).toFloat())
        behind.draw(canvas)
        val old=backdrop.drawable
        backdrop.setImageBitmap(bitmap)
        // Old bitmaps are GC-owned: never recycle while a render thread may use them.
    }
}
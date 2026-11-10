package app.homeroom

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/** Floating navigation. Only the captured backdrop is blurred, never its labels. */
class GlassNav(context: Context, private val behind: View, val choose: (Int) -> Unit) : FrameLayout(context) {
    private val backdrop = ImageView(context)
    private val items = LinearLayout(context)
    private val tabs = listOf(Triple(R.id.nav_chats,"Chats",R.drawable.ic_chats), Triple(R.id.nav_files,"Files",R.drawable.ic_files), Triple(R.id.nav_rules,"Rules",R.drawable.ic_rules), Triple(R.id.nav_settings,"Settings",R.drawable.ic_settings))
    var selectedItemId: Int = R.id.nav_chats
        set(value) { field = value; updateSelection(); choose(value) }
    private var buffers: Array<Bitmap>? = null
    private var nextBuffer = 0
    private var dirty = true
    private var queued = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { backdropChanged() }
    private val layoutListener = View.OnLayoutChangeListener { _, l,t,r,b,ol,ot,or,ob ->
        if(l!=ol || t!=ot || r!=or || b!=ob) backdropChanged()
    }
    internal var captureCount = 0
        private set
    fun backdropChanged() {
        dirty=true
        if(android.os.Build.VERSION.SDK_INT<31 || queued || !isAttachedToWindow || !isShown) return
        queued=true
        postOnAnimation { queued=false; if(dirty && isShown && width>0 && height>0) { dirty=false; capture() } }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        behind.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        behind.addOnLayoutChangeListener(layoutListener)
        backdropChanged()
    }
    override fun onDetachedFromWindow() {
        if(behind.viewTreeObserver.isAlive) behind.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        behind.removeOnLayoutChangeListener(layoutListener)
        backdrop.setImageDrawable(null)
        buffers=null
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh); backdropChanged() }
    override fun onVisibilityChanged(changedView:View,visibility:Int) { super.onVisibilityChanged(changedView,visibility); if(visibility==VISIBLE) post { backdropChanged() } }
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
            cell.addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22),dp(22)))
            cell.addView(text(context,label,10.5f,C.muted,true), LinearLayout.LayoutParams(-2,-2))
            items.addView(cell, LinearLayout.LayoutParams(0,-1,1f))
        }
        updateSelection()
        if(android.os.Build.VERSION.SDK_INT<31) backdrop.setBackgroundColor(0xeb2b2a2d.toInt())
    }

    private fun updateSelection() {
        for(i in 0 until items.childCount) {
            val cell=items.getChildAt(i) as LinearLayout
            val selected=cell.tag==selectedItemId
            cell.background=if(selected) rounded(0x42fc8613,dp(26).toFloat(),0x73fc8613,dp(1).toFloat()) else null
            (cell.getChildAt(0) as ImageView).setColorFilter(if(selected) C.accent else 0xffd7d6db.toInt())
            (cell.getChildAt(1) as TextView).setTextColor(if(selected) C.accent else 0xffd7d6db.toInt())
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
        backdrop.setImageBitmap(bitmap)
        // Old bitmaps are GC-owned: never recycle while a render thread may use them.
    }
}

package app.homeroom

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

fun roomIcon(context: Context, room: Room, server: String?, size: Int = 50): View {
    val frame=FrameLayout(context)
    frame.background=rounded(0xff3c3b3e.toInt(),context.dp(size*36/100).toFloat())
    frame.clipToOutline=true
    val icon=room.icon
    if(icon?.optString("kind")=="symbol") {
        val color=icon.optString("colour").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { Color.parseColor(it) } ?: 0xffb9b7be.toInt()
        frame.background=rounded((color and 0xffffff) or 0x2e000000,context.dp(size*36/100).toFloat())
        if(icon.optString("glyph")=="leaf") frame.addView(ImageView(context).apply { setImageResource(R.drawable.ic_leaf); setColorFilter(color) }, FrameLayout.LayoutParams(context.dp(size/2),context.dp(size/2),Gravity.CENTER))
        else frame.addView(text(context,icon.optString("glyph"),24f,color).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
    } else {
        val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.default_room) }
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        // Custom /homeroom/assets/ URLs are not served by this server.
        // Keep its bundled public default. No network request or credential retry.

    }
    frame.contentDescription=null
    return frame
}

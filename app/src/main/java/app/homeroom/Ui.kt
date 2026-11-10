package app.homeroom

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val bg = Color.parseColor("#1e1d20")
    val surface = Color.parseColor("#2b2a2d")
    val surfaceAlt = Color.parseColor("#353337")
    val accent = Color.parseColor("#fc8613")
    val text = Color.parseColor("#f3f2f5")
    val muted = Color.parseColor("#a8a6ad")
    val teal = Color.parseColor("#35c2c9")
    val violet = Color.parseColor("#a08cff")
    val danger = Color.parseColor("#e5484d")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp
        if (strokeColor != null) setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
    }

fun ViewGroup.lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h)

fun column(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

fun text(context: Context, value: CharSequence, sp: Float = 15f, color: Int = C.text, bold: Boolean = false): TextView =
    TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun button(context: Context, label: CharSequence, primary: Boolean = true, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) Color.parseColor("#1a1206") else C.text)
        background = rounded(if (primary) C.accent else C.surfaceAlt, context.dp(10).toFloat())
        setOnClickListener { onClick() }
    }

fun input(context: Context, hint: String): EditText = EditText(context).apply {
    setHint(hint)
    setHintTextColor(C.muted)
    setTextColor(C.text)
    setBackgroundColor(Color.TRANSPARENT)
    textSize = 16f
}

fun pad(v: View, hDp: Int, vDp: Int) {
    val h = v.dp(hDp); val t = v.dp(vDp)
    v.setPadding(h, t, h, t)
}

fun spacer(context: Context, hDp: Int): View = View(context).apply {
    layoutParams = LinearLayout.LayoutParams(1, context.dp(hDp))
}

fun divider(context: Context): View = View(context).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    setBackgroundColor(C.surfaceAlt)
}

fun snack(host: View, msg: CharSequence) {
    if (!host.isAttachedToWindow) return
    com.google.android.material.snackbar.Snackbar.make(host, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
}

fun formatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val instant = java.time.Instant.parse(iso)
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        val now = java.time.LocalDate.now()
        if (zoned.toLocalDate() == now)
            zoned.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        else if (zoned.toLocalDate() == now.minusDays(1))
            "Yesterday " + zoned.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        else
            zoned.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    } catch (e: Exception) { iso }
}

fun dayLabel(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val d = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val now = java.time.LocalDate.now()
        when (d) {
            now -> "Today"
            now.minusDays(1) -> "Yesterday"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d"))
        }
    } catch (e: Exception) { "" }
}

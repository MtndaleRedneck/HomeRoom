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

// RECONSTRUCTED Nov 23, 2026: not original source. The exact private12 release
// build themes every color (dark value, light value) and picks from the system
// night-mode flag; the previously recovered fixed palette was from an older
// milestone. Colors below are the exact release values; muted vs disabled is a
// best-effort semantic mapping from usage context (both are themed grays).
object C {
    /** True when the system is in light mode; set by MainActivity on create/config change. */
    var light = false

    private fun themed(darkValue: String, lightValue: String): Int = Color.parseColor(if (light) lightValue else darkValue)

    val bg get() = themed("#0e141b", "#f4f7fa")
    val surface get() = themed("#18212b", "#ffffff")
    val surfaceAlt get() = themed("#1e2833", "#e8eff5")
    val text get() = themed("#e9eef3", "#17232f")
    val muted get() = themed("#8e9dac", "#536474")
    val disabled get() = themed("#98a7b6", "#768594")
    val amber get() = themed("#f0a23c", "#8a5700")
    val green get() = themed("#3fbf7f", "#167741")
    val danger get() = themed("#e5484d", "#b82430")
    val accent get() = themed("#fc8613", "#9a4800")
    val teal get() = themed("#35c2c9", "#087b81")
    val violet get() = themed("#a08cff", "#6f48b1")
    val info get() = themed("#2aabee", "#09679d")
    val themedPink get() = themed("#e5577a", "#b02c59")
    // RECONSTRUCTED: the owner accent reuses the accent pair (release personColor
    // maps owner/ezzy to the same accent color).
    val owner get() = accent
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
        pressIn()
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

// 1.7 (Ezzy, Nov 25: "Get haptic feedback. Button pressing in."): shared
// press feedback, matching the BingeMote spec - taps buzz (gated by the saved
// "Vibrate on press" setting), and pressed views sink to 0.94 and dim ~15%,
// 90 ms down / 120 ms up.
object Haptics {
    private const val PREFS = "homeroom_ui"
    private const val KEY_VIBRATE = "vibrate_on_press"
    fun on(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIBRATE, true)
    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIBRATE, value).apply()
    }
}

fun tapHaptic(view: View) {
    if (Haptics.on(view.context)) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * Press-in feedback: the view sinks to 0.94 and dims ~15% while held, easing
 * back on release or cancel. The haptic fires on the down, like BingeMote's
 * PressTile. Returns false from the touch listener so clicks still land.
 */
private val pressAttachListeners = java.util.WeakHashMap<View, View.OnAttachStateChangeListener>()

fun View.pressIn(haptic:Boolean=true) {
    // Installing twice on one view replaces the first install completely.
    pressAttachListeners.remove(this)?.let { removeOnAttachStateChangeListener(it) }
    // The view's resting alpha (a disabled button sits at 0.6). It is read only
    // when no press or release animation is in flight, so a quick second tap
    // during the 120 ms release cannot adopt an in-between alpha as "resting".
    var baseAlpha=1f
    var pressed=false
    var releasing=false
    fun release(v:View) {
        if(!pressed) return
        pressed=false
        releasing=true
        v.animate().scaleX(1f).scaleY(1f).alpha(baseAlpha).setDuration(120).withEndAction { releasing=false }.start()
    }
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Disabled (busy) views never buzz or sink.
                if (v.isEnabled) {
                    if (releasing) { v.animate().cancel(); releasing=false } else if (!pressed) baseAlpha=v.alpha
                    pressed=true
                    if (haptic) tapHaptic(v)
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(baseAlpha*0.85f).setDuration(90).start()
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> release(v)
        }
        false
    }
    // A view that is rebuilt, hidden or detached mid-hold never gets its UP:
    // snap it back so it cannot stay shrunk or dimmed.
    val l=object: View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v:View) {}
        override fun onViewDetachedFromWindow(v:View) {
            if(pressed || releasing) {pressed=false;releasing=false;v.animate().cancel();v.scaleX=1f;v.scaleY=1f;v.alpha=baseAlpha}
        }
    }
    pressAttachListeners[this]=l
    addOnAttachStateChangeListener(l)
}

fun snack(host: View, msg: CharSequence) {
    com.google.android.material.snackbar.Snackbar.make(host, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
}

// RECONSTRUCTED Nov 23, 2026: behavior recovered from the exact private12
// release build (12/24h-aware time pattern; today -> time, yesterday ->
// "Yesterday " + time, older -> "MMM d, time"; blank in/parse failure -> input).
fun formatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val t = parseRoomInstant(iso) ?: return iso
    val today = java.time.LocalDate.now()
    return when (t.toLocalDate()) {
        today -> t.format(java.time.format.DateTimeFormatter.ofPattern(roomTimePattern()))
        today.minusDays(1) -> "Yesterday " + t.format(java.time.format.DateTimeFormatter.ofPattern(roomTimePattern()))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, " + roomTimePattern()))
    }
}

fun dayLabel(iso: String?, now: java.time.LocalDate = java.time.LocalDate.now()): String {
    if (iso.isNullOrBlank()) return ""
    val d = runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return ""
    // RECONSTRUCTED: release uses "EEE, MMM d" (year appended when not current year).
    return when (d) {
        now -> "Today"
        now.minusDays(1) -> "Yesterday"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == now.year) "EEE, MMM d" else "EEE, MMM d, yyyy"))
    }
}
// RECONSTRUCTED Nov 23, 2026: not original source. Time formatting recovered
// exactly from the exact private12 release build: 24h flag from
// DateFormat.is24HourFormat (set by MainActivity), time pattern "H:mm"/"h:mm a".
// Original-lineage names (actual source read Nov 11 10:40:19 EST): the 24h
// flag is use24Hour and the pattern helper is the no-arg clockPattern().
@Volatile var use24Hour = true

fun clockPattern(): String = if (use24Hour) "H:mm" else "h:mm a"

private fun roomTimePattern(): String = clockPattern()

private fun parseRoomInstant(iso: String): java.time.ZonedDateTime? =
    runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull()

fun formatRoomTime(iso: String): String {
    val t = parseRoomInstant(iso) ?: return ""
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), java.time.LocalDate.now())
    return when {
        days == 0L -> t.format(java.time.format.DateTimeFormatter.ofPattern(roomTimePattern()))
        days == 1L -> "Yesterday"
        days in 2..6 -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    }
}

fun formatBubbleTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val t = parseRoomInstant(iso) ?: return iso
    val today = java.time.LocalDate.now()
    return when (t.toLocalDate()) {
        today -> t.format(java.time.format.DateTimeFormatter.ofPattern(roomTimePattern()))
        today.minusDays(1) -> "Yesterday " + t.format(java.time.format.DateTimeFormatter.ofPattern(roomTimePattern()))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, " + roomTimePattern()))
    }
}


// RECONSTRUCTED Nov 23, 2026: error card with retry, recovered from the exact
// private12 release build (surface card, message in text color, "Retry" in the
// info color, 48dp min touch height).
fun errorCard(context: Context, message: String, onRetry: () -> Unit): LinearLayout {
    val card = column(context).apply {
        background = rounded(C.surface, dp(20).toFloat(), null, 12f)
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    card.addView(text(context, message, 14f, C.text))
    card.addView(text(context, "Retry", 14f, C.info, true).apply {
        minHeight = dp(48)
        gravity = android.view.Gravity.CENTER
        setOnClickListener { onRetry() }
    })
    return card
}

// RECONSTRUCTED Nov 23, 2026: person color, recovered exactly from the exact
// private12 release build (ai_ actors hash into a six-color palette; named
// special cases; everyone else muted).
fun personColor(actor: String?): Int {
    if (actor != null && actor.startsWith("ai_")) {
        val palette = intArrayOf(C.teal, C.violet, C.green, C.themedPink, C.info, C.amber)
        return palette[Math.floorMod(actor.hashCode(), 6)]
    }
    return when (actor?.lowercase()) {
        "instinct" -> C.violet
        "owner", "ezzy" -> C.accent
        "tab" -> C.teal
        else -> C.muted
    }
}

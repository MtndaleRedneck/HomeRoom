package app.homeroom

import android.content.Context
import android.widget.FrameLayout

/** Base for the app's screens. The host activity owns navigation and polling. */
abstract class Screen(context: Context) : FrameLayout(context) {
    val host: MainActivity get() = context as MainActivity
    open fun onShow() {}
    open fun onHide() {}
    /** Refresh data if visible; called by the foreground poller. */
    open fun poll() {}
    /** True when this screen consumed the back press. */
    open fun onBack(): Boolean = false
}

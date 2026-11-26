package app.homeroom

import android.content.Context
import android.widget.FrameLayout

/** Base for the app's screens. The host activity owns navigation and polling. */
abstract class Screen(context: Context) : FrameLayout(context) {
    val host: MainActivity get() = context as MainActivity

    // RECONSTRUCTED Nov 23, 2026: per-screen scope tied to the host scope, with a
    // logging exception handler; recovered from the exact private12 release build.
    val screenScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        host.scope.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            android.util.Log.e("Screen", "screen job failed", t)
        })
    open fun onShow() {}
    open fun onHide() {}
    // RECONSTRUCTED: the release notifies the visible screen of connectivity changes.
    open fun onConnectivity(online: Boolean) {}
    /** Refresh data if visible; called by the foreground poller. */
    open fun poll() {}
    /** True when this screen consumed the back press. */
    open fun onBack(): Boolean = false
}
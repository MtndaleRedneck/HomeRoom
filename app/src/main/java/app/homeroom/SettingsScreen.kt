package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch

/** Settings tab: connection info, update check, and sign out. */
class SettingsScreen(context: Context) : Screen(context) {

    private val col = column(context)

    init {
        val scroll = ScrollView(context)
        pad(col, 24, 16)
        scroll.addView(col)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onShow() = rebuild()

    private fun rebuild() {
        col.removeAllViews()
        col.addView(text(context, "Settings", 24f, C.text, true))
        col.addView(spacer(context, 20))

        col.addView(text(context, "Connected to", 13f, C.muted, true))
        col.addView(text(context, host.session.serverUrl ?: "", 15f, C.text))
        col.addView(spacer(context, 14))

        val owner = host.session.ownerName
        if (!owner.isNullOrBlank()) {
            col.addView(text(context, "Signed in as", 13f, C.muted, true))
            col.addView(text(context, owner, 15f, C.text))
            col.addView(spacer(context, 14))
        }

        col.addView(text(context, "App version", 13f, C.muted, true))
        col.addView(text(context, BuildConfig.VERSION_NAME, 15f, C.text))
        col.addView(spacer(context, 24))

        col.addView(button(context, "Check for updates", primary = false) {
            UpdateCheck.checkManually(host, host.scope, host.session) { msg -> snack(this, msg) }
        }, lp())
        col.addView(spacer(context, 12))

        col.addView(button(context, "Sign out", primary = false) { confirmSignOut() }, lp())
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(context)
            .setTitle("Sign out?")
            .setMessage("This device will need a new connection link to sign back in.")
            .setPositiveButton("Sign out") { _, _ ->
                host.scope.launch {
                    val token = host.session.fcmRegisteredToken
                    if (token != null) {
                        try {
                            host.api.unregisterPushToken(token)
                        } catch (e: Exception) {
                            // Best effort; the server expires dead tokens.
                        }
                        host.session.fcmRegisteredToken = null
                    }
                    try {
                        host.api.logout()
                    } catch (e: Exception) {
                        // Best effort; the local session is cleared either way.
                    }
                    host.session.clear()
                    host.onSignedOut()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

package app.homeroom

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import kotlinx.coroutines.launch

/**
 * First-run connection screen: paste the one-use connection link, enroll this
 * device, and start the session. The link is consumed on use; used or expired
 * links get a plain message instead of a dead end.
 */
class SetupScreen(context: Context) : Screen(context) {

    private val serverInput = input(context, "Server address")
    private val linkInput = input(context, "Connection link")
    private val status = text(context, "", 14f, C.muted)
    private val connectButton: View

    init {
        val scroll = ScrollView(context)
        val col = column(context)
        pad(col, 24, 16)
        scroll.addView(col)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        col.addView(text(context, "HomeRoom", 30f, C.text, true))
        col.addView(spacer(context, 6))
        col.addView(text(context, "Your shared household chat, on your own server.", 15f, C.muted))
        col.addView(spacer(context, 28))

        col.addView(text(context, "Server", 13f, C.muted, true))
        serverInput.setText(host.session.serverUrl ?: "")
        serverInput.setSingleLine()
        col.addView(serverInput, lp())
        col.addView(spacer(context, 18))

        col.addView(text(context, "Connection link", 13f, C.muted, true))
        col.addView(text(context, "Ask for a link on the server's connect page, then paste the whole link here. Each link works once, for a short time.", 13f, C.muted))
        col.addView(spacer(context, 8))
        linkInput.minLines = 2
        linkInput.gravity = Gravity.TOP
        col.addView(linkInput, lp())
        col.addView(spacer(context, 20))

        connectButton = button(context, "Connect") { connect() }
        col.addView(connectButton, lp())
        col.addView(spacer(context, 14))
        col.addView(status, lp())
    }

    private fun setBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        connectButton.alpha = if (busy) 0.5f else 1f
    }

    private fun connect() {
        val server = serverInput.text.toString().trim().trimEnd('/')
        val link = linkInput.text.toString().trim()
        status.text = ""
        if (server.isEmpty()) {
            status.text = "Enter your server address first."
            return
        }
        if (link.isEmpty()) {
            status.text = "Paste the connection link first."
            return
        }
        val token = extractToken(link)
        if (token == null) {
            status.text = "That doesn't look like a connection link. Paste the whole link, including the part after #."
            return
        }
        host.session.serverUrl = if (server.startsWith("http")) server else "https://" + server
        setBusy(true)
        hideKeyboard()
        status.text = "Connecting..."
        host.scope.launch {
            try {
                host.api.enroll(token, "HomeRoom Android app")
                host.onEnrolled()
            } catch (e: ApiException) {
                setBusy(false)
                status.text = when {
                    e.status == 0 && e.code == "invalid_response" ->
                        "The server didn't answer as expected. Check the address and try again."
                    e.status in listOf(401, 403, 404, 410) ->
                        "This link didn't work. It may be used or expired. Ask for a new one."
                    else -> "Couldn't connect (" + e.code + "). Try again."
                }
            } catch (e: SessionEndedException) {
                setBusy(false)
                status.text = "The session ended right after connecting. Ask for a new link and try again."
            } catch (e: Exception) {
                setBusy(false)
                status.text = "Couldn't reach the server. Check your connection and the address."
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    companion object {
        /** Connection links carry the one-use token in the URL fragment. */
        fun extractToken(link: String): String? {
            val fragment = Uri.parse(link).fragment ?: return null
            for (part in fragment.split('&')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && (kv[0] == "token" || kv[0] == "connect")) {
                    return Uri.decode(kv[1]).ifBlank { null }
                }
            }
            return fragment.ifBlank { null }
        }
    }
}

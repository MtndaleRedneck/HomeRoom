package app.homeroom

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub releases and offers Update / Later / Skip when
 * a newer version exists. Silent on any failure. Manual checks always report
 * the outcome, including "you're up to date".
 */
object UpdateCheck {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/MtndaleRedneck/HomeRoom/releases/latest"

    fun checkInBackground(activity: Activity, scope: CoroutineScope, session: Session) {
        scope.launch {
            val result = fetch() ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (result.tag == session.skippedUpdateTag) return@launch
            if (isNewer(result.tag, BuildConfig.VERSION_NAME)) {
                showDialog(activity, session, result)
            }
        }
    }

    fun checkManually(activity: Activity, scope: CoroutineScope, session: Session, onResult: (String) -> Unit) {
        scope.launch {
            val result = fetch()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when {
                result == null -> onResult("Couldn't check for updates right now.")
                isNewer(result.tag, BuildConfig.VERSION_NAME) -> showDialog(activity, session, result)
                else -> onResult("You're on the latest version (" + BuildConfig.VERSION_NAME + ").")
            }
        }
    }

    private data class Release(val tag: String, val page: String, val notes: String)

    private suspend fun fetch(): Release? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "HomeRoom-Android")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val release = JSONObject(body)
            val tag = release.optString("tag_name")
            val page = release.optString("html_url")
            if (tag.isEmpty() || page.isEmpty()) null
            else Release(tag, page, release.optString("body"))
        } catch (e: Exception) {
            null
        }
    }

    private fun showDialog(activity: Activity, session: Session, release: Release) {
        val notes = release.notes.take(4000)
        AlertDialog.Builder(activity)
            .setTitle("Update available: " + release.tag)
            .setMessage(if (notes.isBlank()) "A newer version of HomeRoom is available." else notes)
            .setPositiveButton("Update") { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page)))
            }
            .setNeutralButton("Later", null)
            .setNegativeButton("Skip") { _, _ -> session.skippedUpdateTag = release.tag }
            .show()
    }

    internal fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String): List<Int> =
            v.removePrefix("v").split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

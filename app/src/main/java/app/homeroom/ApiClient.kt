package app.homeroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SessionEndedException(val reason: String?) : Exception("session_ended")

class ApiException(val status: Int, val code: String, val retryAfter: Int = 0) : Exception(code)

/**
 * Speaks to the HomeRoom server API under /homeroom/v1.
 *
 * Auth is the __Host-homeroom session cookie. Non-GET requests carry the
 * CSRF token from the latest /owner/session bootstrap. A 409 session_rotated
 * or 403 csrf_failed is retried once after a fresh bootstrap; a 401 ends the
 * session and surfaces as SessionEndedException.
 */
class ApiClient(private val session: Session) {

    @Volatile
    private var csrf: String? = session.csrf

    @Volatile
    var sessionData: JSONObject? = null
        private set

    private val bootMutex = Mutex()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.firstOrNull { it.name == COOKIE_NAME }?.let { session.cookie = it.value }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val value = session.cookie ?: return emptyList()
                return listOf(
                    Cookie.Builder()
                        .name(COOKIE_NAME)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .secure()
                        .build()
                )
            }
        })
        .build()

    private fun baseUrl(): String =
        (session.serverUrl ?: throw ApiException(0, "no_server")).trimEnd('/') + API_PREFIX

    /**
     * Headers matching what a same-origin browser page sends, so the server's
     * origin checks treat the native client like the web UI: Origin on every
     * call, and the fetch-metadata headers a browser attaches to same-origin
     * API requests.
     */
    private fun applyOriginHeaders(builder: Request.Builder, method: String) =
        Companion.applyOriginHeaders(builder, baseUrl(), method)

    private fun raw(route: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl() + route)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        applyOriginHeaders(builder, method)
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, body.toString().toRequestBody(JSON))
        } else if (method != "GET") {
            builder.method(method, "{}".toRequestBody(JSON))
        } else {
            builder.get()
        }
        if (method != "GET") csrf?.let { builder.header("X-CSRF-Token", it) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val data = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(response.code, "invalid_response")
            }
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 0
                throw ApiException(response.code, data.optString("error", "request_failed"), retryAfter)
            }
            return data
        }
    }

    suspend fun bootstrap(): JSONObject = bootMutex.withLock {
        withContext(Dispatchers.IO) {
            var data = try {
                raw("/owner/session")
            } catch (e: ApiException) {
                if (e.status == 409 && e.code == "session_rotated") raw("/owner/session") else throw e
            }
            csrf = data.optString("csrf").ifBlank { null }
            session.csrf = csrf
            sessionData = data
            session.ownerId = data.optString("owner_id").ifBlank { session.ownerId }
            session.ownerName = data.optString("owner_display_name").ifBlank { session.ownerName }
            session.sessionId = data.optString("session_id").ifBlank { session.sessionId }
            data
        }
    }

    suspend fun request(route: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (sessionData == null) bootstrapOrSessionEnded()
            try {
                raw(route, method, body)
            } catch (e: ApiException) {
                if ((e.status == 409 && e.code == "session_rotated") ||
                    (e.status == 403 && e.code == "csrf_failed")
                ) {
                    sessionData = null
                    bootstrapOrSessionEnded()
                    try {
                        raw(route, method, body)
                    } catch (again: ApiException) {
                        if (again.status == 401) throw SessionEndedException(again.code)
                        throw again
                    }
                } else {
                    if (e.status == 401) throw SessionEndedException(e.code)
                    throw e
                }
            }
        }

    /** bootstrap() maps a 401 (idle, expired or revoked session) to
     *  SessionEndedException so the UI returns to setup instead of
     *  dead-ending on a generic load error. */
    private suspend fun bootstrapOrSessionEnded() {
        try {
            bootstrap()
        } catch (e: ApiException) {
            if (e.status == 401) throw SessionEndedException(e.code)
            throw e
        }
    }

    /**
     * Background-safe event fetch for the push worker: hits only the route
     * the server exempts from idle accounting and cookie rotation - no
     * bootstrap, since /owner/session counts as use and can rotate the
     * cookie. A GET needs no CSRF token. The cookie jar picks up a rotated
     * cookie from Set-Cookie, so one retry covers 409 session_rotated.
     */
    suspend fun eventDirect(eventId: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            raw("/owner/events/" + enc(eventId))
        } catch (e: ApiException) {
            if (e.status == 409 && e.code == "session_rotated") {
                raw("/owner/events/" + enc(eventId))
            } else throw e
        }
    }

    suspend fun enroll(token: String, deviceLabel: String): Unit = withContext(Dispatchers.IO) {
        raw("/owner/enroll", "POST", JSONObject()
            .put("token", token)
            .put("device_label", deviceLabel))
        bootstrap()
        Unit
    }

    suspend fun rooms(): JSONArray = request("/owner/rooms").requiredArray("rooms")

    suspend fun waiting(room: String, cursor: String? = null, limit: Int = 20): JSONObject =
        request("/owner/rooms/" + enc(room) + "/waiting?limit=" + limit +
            (if (cursor != null) "&cursor=" + enc(cursor) else ""))

    suspend fun roomEvents(room: String, after: String? = null, before: String? = null, limit: Int = 50): JSONObject =
        request("/owner/rooms/" + enc(room) + "/events?limit=" + limit +
            (if (after != null) "&after=" + enc(after) else "") +
            (if (before != null) "&before=" + enc(before) else ""))

    suspend fun event(eventId: String): JSONObject =
        request("/owner/events/" + enc(eventId))

    suspend fun threadEvents(thread: String, after: String? = null, limit: Int = 50): JSONObject =
        request("/owner/threads/" + enc(thread) + "?limit=" + limit +
            (if (after != null) "&after=" + enc(after) else ""))

    suspend fun post(room: String, clientId: String, text: String, threadKey: String?, parentId: String?): JSONObject =
        request("/rooms/" + enc(room) + "/posts", "POST", JSONObject()
            .put("client_id", clientId)
            .put("text", text)
            .put("thread_key", threadKey ?: JSONObject.NULL)
            .put("parent_id", parentId ?: JSONObject.NULL))

    suspend fun markRead(threadKey: String, seq: Long): JSONObject =
        request("/read", "POST", JSONObject()
            .put("thread_key", threadKey)
            .put("last_read_seq", seq))

    suspend fun search(query: String, room: String? = null, limit: Int = 20): JSONArray =
        request("/owner/search?q=" + enc(query) + "&limit=" + limit +
            (if (room != null) "&room=" + enc(room) else ""))
            .requiredArray("results")

    suspend fun files(room: String, limit: Int = 50): JSONArray =
        request("/owner/rooms/" + enc(room) + "/files?limit=" + limit)
            .requiredArray("files")

    /** Returns (bytes, mimeType) for an image preview, or throws. */
    suspend fun filePreview(id: String, maxBytes: Int = 10 * 1024 * 1024): Pair<ByteArray, String> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl() + "/owner/files/" + enc(id))
                .header("Accept", "*/*")
            applyOriginHeaders(builder, "GET")
            val request = builder.get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw SessionEndedException("unauthorized")
                if (!response.isSuccessful) throw ApiException(response.code, "preview_failed")
                val type = (response.header("Content-Type") ?: "").split(';')[0]
                if (type !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    throw ApiException(0, "unsupported_preview_type")
                }
                val bytes = response.body!!.bytes()
                if (bytes.isEmpty() || bytes.size > maxBytes) throw ApiException(0, "preview_size_limit")
                bytes to type
            }
        }

    suspend fun rules(room: String? = null): JSONObject =
        request("/owner/rules" + (if (room != null) "?room=" + enc(room) else ""))

    suspend fun ruleHistory(ruleId: String): JSONObject =
        request("/owner/rules/" + enc(ruleId) + "/history")

    suspend fun ruleDecision(ruleId: String, version: Int, decision: String): JSONObject =
        request("/owner/rules/" + enc(ruleId) + "/decision", "POST", JSONObject()
            .put("version", version)
            .put("decision", decision))

    suspend fun recordReread(room: String): JSONObject =
        request("/rules/reread", "POST", JSONObject()
            .put("room", room)
            .put("at", java.time.Instant.now().toString()))

    suspend fun muteRoom(room: String, muted: Boolean): JSONObject =
        request("/push/mute", "PUT", JSONObject()
            .put("room_id", room)
            .put("muted", muted)
            .put("until", JSONObject.NULL))

    suspend fun sessions(): JSONArray = request("/owner/sessions").optJSONArray("sessions") ?: JSONArray()

    suspend fun revokeSession(id: String): JSONObject =
        request("/owner/sessions/" + enc(id), "DELETE")

    suspend fun registerPushToken(token: String, deviceLabel: String, appVersion: String): JSONObject =
        request("/push/register", "POST", JSONObject()
            .put("token", token)
            .put("platform", "fcm")
            .put("device_label", deviceLabel)
            .put("app_version", appVersion))

    suspend fun unregisterPushToken(token: String): JSONObject =
        request("/push/unregister", "POST", JSONObject().put("token", token))

    suspend fun logout() {
        try {
            request("/owner/logout", "POST", JSONObject())
        } finally {
            sessionData = null
            csrf = null
            session.clear()
        }
    }

    companion object {
        private const val COOKIE_NAME = "__Host-homeroom"
        private const val API_PREFIX = "/homeroom/v1"
        private val JSON = "application/json".toMediaType()

        fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

        /**
         * Server contract on the owner lane: Sec-Fetch-Site: same-origin on
         * every request; Origin required on mutations, omitted on GET (as a
         * browser does). Any X-Homeroom-* header causes a 401, so none are
         * ever set.
         */
        internal fun applyOriginHeaders(builder: Request.Builder, baseUrl: String, method: String) {
            val base = baseUrl.toHttpUrl()
            val origin = base.scheme + "://" + base.host +
                (if (base.port != okhttp3.HttpUrl.defaultPort(base.scheme)) ":" + base.port else "")
            if (method != "GET") builder.header("Origin", origin)
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Dest", "empty")
        }
    }
}

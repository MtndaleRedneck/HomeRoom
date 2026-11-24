package app.homeroom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local session state: the server URL, the __Host-homeroom session cookie,
 * and the latest CSRF token. The cookie is the only credential; it is kept
 * in private app storage, never logged, and cleared on sign-out.
 *
 * On first run after upgrading from the previous app build, the saved server
 * URL is carried over from that build's preferences so the owner only needs
 * to paste a fresh one-use connection link.
 */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.getString(KEY_SERVER_URL, null).isNullOrBlank()) {
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacyUrl = legacy.getString(LEGACY_KEY_START_URL, null)
            if (!legacyUrl.isNullOrBlank()) {
                SetupScreen.normalizeServer(legacyUrl.trim())?.let {prefs.edit().putString(KEY_SERVER_URL, it).apply()}
            }
        }
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(v) = prefs.edit().putString(KEY_COOKIE, v).apply()

    var csrf: String?
        get() = prefs.getString(KEY_CSRF, null)
        set(v) = prefs.edit().putString(KEY_CSRF, v).apply()

    // Operation identity check (I2 final, operation rebinding): the explicit
    // recovery path must prove the session it started in still exists before
    // resending the original body - generation AND destination together,
    // under the same lock as clear().
    fun unchangedSince(opGeneration: Long, opServerUrl: String?): Boolean =
        synchronized(stateLock) {
            generation == opGeneration && serverUrl == opServerUrl
        }

    // Operation-generation boundary (I2 recheck of 93a5b8b): response cookies
    // are accepted only when the generation captured BEFORE the request still
    // matches under stateLock - a response from before sign-out cannot restore
    // dead credentials through the CookieJar, which writes outside the paired
    // credential commit.
    fun acceptCookieForGeneration(opGeneration: Long, value: String): Boolean =
        synchronized(stateLock) {
            if (generation != opGeneration) return false
            cookie = value
            true
        }

    // RECOVERED Nov 24, 2026 (operator Nov 15 readback): the foreground renew
    // worker committed rotated credentials durably before later requests ran.
    // A failed commit is loud (IllegalStateException) - same durability rule as
    // the staged-record transitions.
    fun replaceCredentials(newCookie: String?, newCsrf: String?) {
        if (!prefs.edit().putString(KEY_COOKIE, newCookie).putString(KEY_CSRF, newCsrf).commit())
            throw IllegalStateException("session_credentials_commit_failed")
    }

    var ownerId: String?
        get() = prefs.getString(KEY_OWNER_ID, null)
        set(v) = prefs.edit().putString(KEY_OWNER_ID, v).apply()

    var ownerName: String?
        get() = prefs.getString(KEY_OWNER_NAME, null)
        set(v) = prefs.edit().putString(KEY_OWNER_NAME, v).apply()

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(v) = prefs.edit().putString(KEY_SESSION_ID, v).apply()

    val enrolled: Boolean
        get() = !serverUrl.isNullOrBlank() && !cookie.isNullOrBlank()

    // Shared atomic boundary (I2 review, Nov 24 2026): the generation
    // comparison + credential write (withinGeneration) and clear() both hold
    // stateLock, so a renew in flight cannot resurrect old credentials after
    // sign-out - previously the check and the write were not atomic vs clear.
    private val stateLock = Any()

    fun clear() {
        // RECONSTRUCTED: the release bumps the session generation on clear so
        // stale in-flight writes from the old session are dropped.
        synchronized(stateLock) {
            val generation = generation
            val keepUrl = serverUrl
            prefs.edit().clear().apply()
            prefs.edit().putLong(KEY_SESSION_GENERATION, generation + 1L).apply()
            serverUrl = keepUrl
        }
    }

    // RECONSTRUCTED Nov 23, 2026: generation guard + decision review persistence,
    // recovered from the exact private12 release build (prefs keys
    // "session_generation", "decision:<id>"; review writes commit() and fail loudly).
    val generation: Long
        get() = prefs.getLong(KEY_SESSION_GENERATION, 0L)

    fun withinGeneration(generation: Long, block: () -> Unit) {
        synchronized(stateLock) {
            if (this.generation == generation) block()
        }
    }

    fun saveDecisionReview(id: String, value: org.json.JSONObject?) {
        if (!prefs.edit().putString(KEY_DECISION + id, value?.toString()).commit()) {
            throw IllegalStateException("Check failed.")
        }
    }

    fun decisionReview(id: String): org.json.JSONObject? =
        prefs.getString(KEY_DECISION + id, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }

    // RECONSTRUCTED: the release persists the last open room under "last_room_id".
    var lastRoomId: String?
        get() = prefs.getString(KEY_LAST_ROOM, null)
        set(v) = prefs.edit().putString(KEY_LAST_ROOM, v).apply()




    // RECONSTRUCTED: private staging records for interrupted uploads (reconstruction
    // feature surface; persisted locally, removed when the post is sent).
    // RECONSTRUCTED: last foreground roster cache (background sender names).
    var roomsCache: String?
        get() = prefs.getString(KEY_ROOMS_CACHE, null)
        set(v) = prefs.edit().putString(KEY_ROOMS_CACHE, v).apply()

    // RECONSTRUCTED: pending immutable post bodies, drained by Outbox.
    fun outboxData(): String? = prefs.getString(KEY_OUTBOX, null)
    fun saveOutboxData(value: String) { prefs.edit().putString(KEY_OUTBOX, value).apply() }

    fun stagedUploads(): List<JSONObject> =
        runCatching { JSONArray(prefs.getString(KEY_STAGED, "[]")).objects() }.getOrDefault(emptyList())

    // DURABILITY (external review 2026-11-24): staged-record transitions gate
    // irreversible remote side effects, so they use checked synchronous commit(),
    // the same fail-loudly pattern as saveDecisionReview. A failed commit throws
    // BEFORE the caller's network op, preserving the prior durable state
    // (fail closed). apply()'s async disk write left a crash window where the
    // server could hold a POST the restored record did not know was attempted.
    fun saveStagedUpload(record: JSONObject) {
        val id = record.optString("upload")
        val list = stagedUploads().filter { it.optString("upload") != id }.toMutableList()
        list.add(record)
        if (!prefs.edit().putString(KEY_STAGED, JSONArray(list).toString()).commit()) {
            throw IllegalStateException("Staged upload persistence failed.")
        }
    }

    // Removal is the post-confirmation cleanup: a failed commit KEEPS the record,
    // which the fail-closed predicates and resume reconciliation already handle,
    // so callers wrap this in runCatching and continue.
    fun removeStagedUpload(uploadId: String) {
        val list = stagedUploads().filter { it.optString("upload") != uploadId }
        if (!prefs.edit().putString(KEY_STAGED, JSONArray(list).toString()).commit()) {
            throw IllegalStateException("Staged upload persistence failed.")
        }
    }

    fun anchor(roomId: String): String? = prefs.getString(KEY_ANCHOR + roomId, null)

    fun setAnchor(roomId: String, eventId: String?) {
        if (eventId == null) prefs.edit().remove(KEY_ANCHOR + roomId).apply()
        else prefs.edit().putString(KEY_ANCHOR + roomId, eventId).apply()
    }

    // RECONSTRUCTED Nov 23, 2026: FCM token fields; preference key names recovered
    // from the exact private12 release build ("fcm_token", "fcm_registered").
    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_FCM_TOKEN, v).apply()

    var fcmRegisteredToken: String?
        get() = prefs.getString(KEY_FCM_REGISTERED, null)
        set(v) = prefs.edit().putString(KEY_FCM_REGISTERED, v).apply()

    var skippedUpdateTag: String?
        get() = prefs.getString(KEY_SKIPPED_TAG, null)
        set(v) = prefs.edit().putString(KEY_SKIPPED_TAG, v).apply()

    companion object {
        private const val PREFS = "homeroom_session"
        private const val LEGACY_PREFS = "homeroom"
        private const val LEGACY_KEY_START_URL = "start_url"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_CSRF = "csrf"
        private const val KEY_OWNER_ID = "owner_id"
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_ANCHOR = "anchor:"
        private const val KEY_SKIPPED_TAG = "skipped_update_tag"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_FCM_REGISTERED = "fcm_registered"
        private const val KEY_SESSION_GENERATION = "session_generation"
        private const val KEY_DECISION = "decision:"
        private const val KEY_LAST_ROOM = "last_room_id"
        private const val KEY_STAGED = "staged_uploads"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_ROOMS_CACHE = "rooms_cache"
    }
}
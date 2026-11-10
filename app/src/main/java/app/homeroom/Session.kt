package app.homeroom

import android.content.Context

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
                prefs.edit().putString(KEY_SERVER_URL, legacyUrl).apply()
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

    fun clear() {
        val keepUrl = serverUrl
        prefs.edit().clear().apply()
        serverUrl = keepUrl
    }

    fun anchor(roomId: String): String? = prefs.getString(KEY_ANCHOR + roomId, null)

    fun setAnchor(roomId: String, eventId: String?) {
        if (eventId == null) prefs.edit().remove(KEY_ANCHOR + roomId).apply()
        else prefs.edit().putString(KEY_ANCHOR + roomId, eventId).apply()
    }

    /** Last rooms roster the app fetched while in the foreground; the push
     *  worker reads it to resolve member display names without a background
     *  /owner/rooms call. */
    var roomsCache: String?
        get() = prefs.getString(KEY_ROOMS_CACHE, null)
        set(v) = prefs.edit().putString(KEY_ROOMS_CACHE, v).apply()

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
        private const val KEY_ROOMS_CACHE = "rooms_cache"
        private const val KEY_FCM_REGISTERED = "fcm_registered"
    }
}

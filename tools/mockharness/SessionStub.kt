package app.homeroom

/**
 * JVM test double for the Android-backed Session (mock harness only; never
 * compiled into the app). Backs the seven members ApiClient touches.
 */
class Session {
    var serverUrl: String? = null
    var cookie: String? = "test-cookie"
    var csrf: String? = "test-csrf"
    var sessionId: String? = "test-session"
    var ownerId: String? = "owner1"
    var ownerName: String? = "Test Owner"
    fun clear() { cookie = null; csrf = null; sessionId = null }
    // In-memory stand-ins for the generation guard + durable credential commit
    // (the real Session persists via SharedPreferences commit()).
    var generation: Long = 0L
    fun withinGeneration(generation: Long, block: () -> Unit) {
        if (this.generation == generation) block()
    }
    fun replaceCredentials(newCookie: String?, newCsrf: String?) {
        cookie = newCookie; csrf = newCsrf
    }
    fun unchangedSince(opGeneration: Long, opServerUrl: String?): Boolean =
        generation == opGeneration && serverUrl == opServerUrl
    fun acceptCookieForGeneration(opGeneration: Long, value: String): Boolean {
        if (generation != opGeneration) return false
        cookie = value
        return true
    }
}

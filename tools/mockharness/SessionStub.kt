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
}

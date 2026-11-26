package app.homeroom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Registers this device's FCM token with the HomeRoom server. The server is
 * idempotent for the same token and session, and it deletes the registration
 * whenever a session ends, so registration runs on every app start and after
 * every enroll - no "already registered" shortcut, which heals any
 * server-side retirement the app never hears about.
 */
object PushRegistration {

    fun register(scope: CoroutineScope, session: Session, api: ApiClient) {
        val token = session.fcmToken ?: return
        if (!session.enrolled) return
        val gen = session.authSnapshot().generation
        scope.launch {
            try {
                // The session may have ended or been replaced since this call was queued.
                if (!session.enrolled || session.authSnapshot().generation != gen) return@launch
                api.registerPushToken(token, "HomeRoom Android app", BuildConfig.VERSION_NAME)
                if (session.authSnapshot().generation != gen) return@launch
                session.fcmRegisteredToken = token
            } catch (e: SessionEndedException) {
                // The session is gone; the next successful enroll re-registers.
            } catch (e: Exception) {
                // Offline or server down; retried on next app start.
            }
        }
    }

    fun unregister(scope: CoroutineScope, session: Session, api: ApiClient) {
        val token = session.fcmRegisteredToken ?: return
        scope.launch {
            try {
                api.unregisterPushToken(token)
            } catch (e: Exception) {
                // Best effort; the server expires dead tokens on its own.
            } finally {
                session.fcmRegisteredToken = null
            }
        }
    }

    /** App start and after enrollment: register the stored token and pick up a token FCM issued before enrollment. */
    fun sync(scope: CoroutineScope, session: Session, api: ApiClient) {
        if (!session.enrolled || PushTokenDeletion.pending) return
        val gen = session.authSnapshot().generation
        fetchToken { fresh ->
            if (!session.enrolled || session.authSnapshot().generation != gen || PushTokenDeletion.pending) return@fetchToken
            if (!fresh.isNullOrBlank() && session.fcmToken != fresh) session.fcmToken = fresh
            register(scope, session, api) // exactly one POST: the fresh token if FCM issued one, else the stored one
        }
    }

    /** Test seam (null in production): replaces the FCM token fetch; reports null when unavailable. */
    internal var tokenFetcher: (((String?) -> Unit) -> Unit)? = null
    private fun fetchToken(done: (String?) -> Unit) {
        val f = tokenFetcher
        if (f != null) { f(done); return }
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task -> done(if (task.isSuccessful) task.result else null) }
        } catch (_: Exception) { done(null) } // Firebase unavailable: the stored token still registers
    }

    fun refreshToken(scope: CoroutineScope, session: Session, api: ApiClient) {
        val gen = session.authSnapshot().generation
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!session.enrolled || session.authSnapshot().generation != gen || PushTokenDeletion.pending) return@addOnSuccessListener
                if (!token.isNullOrBlank() && session.fcmToken != token) {
                    session.fcmToken = token
                    // A fresh token is registered right away, not at next start.
                    register(scope, session, api)
                }
            }
    }
}
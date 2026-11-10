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
        scope.launch {
            try {
                api.registerPushToken(token, "HomeRoom Android app", BuildConfig.VERSION_NAME)
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

    fun refreshToken(scope: CoroutineScope, session: Session, api: ApiClient) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrBlank() && session.fcmToken != token) {
                    session.fcmToken = token
                    // A fresh token is registered right away, not at next start.
                    register(scope, session, api)
                }
            }
    }
}

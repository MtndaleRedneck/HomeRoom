package app.homeroom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        if (!session.enrolled || PushTokenDeletion.busy) return
        val gen = session.authSnapshot().generation
        val epoch = PushTokenDeletion.epoch
        scope.launch {
            try {
                // The session may have ended or been replaced, or a token deletion started, since this call was queued.
                if (!session.enrolled || session.authSnapshot().generation != gen || PushTokenDeletion.busy || PushTokenDeletion.epoch != epoch) return@launch
                api.registerPushToken(token, "HomeRoom Android app", BuildConfig.VERSION_NAME)
                // A deletion that started or finished during the POST may have invalidated this token: no "registered" marker for it.
                if (session.authSnapshot().generation != gen || PushTokenDeletion.epoch != epoch || session.fcmToken != token) return@launch
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
        if (!session.enrolled || PushTokenDeletion.busy) return
        val gen = session.authSnapshot().generation
        val epoch = PushTokenDeletion.epoch
        fetchToken { fresh ->
            // Stale if the session changed, a deletion is running, or one started or finished since this fetch began.
            if (!session.enrolled || session.authSnapshot().generation != gen || PushTokenDeletion.busy || PushTokenDeletion.epoch != epoch) return@fetchToken
            if (!fresh.isNullOrBlank() && session.fcmToken != fresh) session.fcmToken = fresh
            // A durable deletion intent (failed delete) means the stored token may be dead: never fall back to it, register only a fresh one.
            if (fresh.isNullOrBlank() && PushTokenDeletion.pending) return@fetchToken
            register(scope, session, api) // exactly one POST: the fresh token if FCM issued one, else the stored one
        }
    }

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Process-owned resync (no Activity needed), used after a token deletion finished or failed. */
    fun syncProcess(context: android.content.Context) {
        val session = Session(context.applicationContext)
        if (!session.enrolled) return
        sync(processScope, session, ApiClient(session))
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
                if (!session.enrolled || session.authSnapshot().generation != gen || PushTokenDeletion.busy) return@addOnSuccessListener
                if (!token.isNullOrBlank() && session.fcmToken != token) {
                    session.fcmToken = token
                    // A fresh token is registered right away, not at next start.
                    register(scope, session, api)
                }
            }
    }
}
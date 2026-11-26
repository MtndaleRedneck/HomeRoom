package app.homeroom

import android.content.Context

/** Separate from cleared session preferences so a failed deletion survives sign-out. */
object PushTokenDeletion {
    @Volatile var pending=false
        private set
    /** Set by the visible activity: called on the main thread after a deletion completed. */
    /** Bumped when a deletion starts and when it completes; FCM callbacks that started under an older value are stale. */
    @Volatile var epoch=0
        private set
    @Volatile var onCompleted:(()->Unit)?=null
    /** Test seam (null in production): replaces FirebaseMessaging.deleteToken; reports success. */
    internal var deleteTokenOp:(((Boolean)->Unit)->Unit)?=null
    private fun prefs(context:Context)=context.getSharedPreferences("push_token_cleanup",Context.MODE_PRIVATE)
    fun request(context:Context) {
        pending=true;prefs(context).edit().putBoolean("pending",true).apply()
        attempt(context)
    }
    fun retryOnStart(context:Context) {
        pending=prefs(context).getBoolean("pending",false)
        if(pending)attempt(context)
    }
    private fun attempt(context:Context) {
        try {
            epoch++ // any FCM fetch already in flight predates this deletion and is discarded by its callback
            val op=deleteTokenOp ?: {done:(Boolean)->Unit -> com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {t->done(t.isSuccessful)}}
            op {ok ->
                if(ok) {
                    // FirebaseMessaging.deleteToken is instance-wide: every token cached before this point is suspect, whichever session it belongs to.
                    // Drop them all; the completion callback fetches the current token and registers it (never a cached one).
                    Session(context).let {it.fcmToken=null;it.fcmRegisteredToken=null}
                    epoch++
                    prefs(context).edit().remove("pending").apply();pending=false
                    onCompleted?.invoke()
                }
                // Failure stays persisted; an enrolled start/enrolment retries it before registering (see MainActivity.syncPush).
            }
        } catch(_:Exception) { /* Keep pending for the next start. */ }
    }
}
package app.homeroom

import android.content.Context

/** Separate from cleared session preferences so a failed deletion survives sign-out. */
object PushTokenDeletion {
    @Volatile var pending=false
        private set
    /** Set by the visible activity: called on the main thread after a deletion completed. */
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
            // The token that this deletion retires. A session enrolled again meanwhile may already hold a NEWER token: only the retired one is cleared.
            val retired=Session(context).fcmToken
            val op=deleteTokenOp ?: {done:(Boolean)->Unit -> com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {t->done(t.isSuccessful)}}
            op {ok ->
                if(ok) {
                    Session(context).let {
                        if(it.fcmToken==retired) it.fcmToken=null
                        if(it.fcmRegisteredToken==retired) it.fcmRegisteredToken=null
                    }
                    prefs(context).edit().remove("pending").apply();pending=false
                    onCompleted?.invoke()
                }
                // Failure stays persisted; an enrolled start/enrolment retries it before registering (see MainActivity.syncPush).
            }
        } catch(_:Exception) { /* Keep pending for the next start. */ }
    }
}
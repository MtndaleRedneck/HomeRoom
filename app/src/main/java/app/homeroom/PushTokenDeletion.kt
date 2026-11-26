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
    /** Tests only: forget an unfinished attempt held by a fake. */
    internal fun resetForTest(){inFlight=false}
    private fun prefs(context:Context)=context.getSharedPreferences("push_token_cleanup",Context.MODE_PRIVATE)
    fun request(context:Context) {
        pending=true;prefs(context).edit().putBoolean("pending",true).apply()
        attempt(context)
    }
    /** True while an instance-wide deleteToken call is running: at most one at a time (request/retry while it runs join it). */
    @Volatile private var inFlight=false
    /** Loads the persisted flag (the in-memory one is false after a cold start), starts or joins the deletion, and returns whether one is pending. */
    fun retryOnStart(context:Context):Boolean {
        pending=prefs(context).getBoolean("pending",false)
        if(pending)attempt(context)
        return pending
    }
    private fun attempt(context:Context) {
        synchronized(this){if(inFlight)return;inFlight=true}
        try {
            epoch++ // any FCM fetch already in flight predates this deletion and is discarded by its callback
            val op=deleteTokenOp ?: {done:(Boolean)->Unit -> com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {t->done(t.isSuccessful)}}
            op {ok ->
                inFlight=false
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
        } catch(_:Exception) { inFlight=false /* Keep pending for the next start. */ }
    }
}
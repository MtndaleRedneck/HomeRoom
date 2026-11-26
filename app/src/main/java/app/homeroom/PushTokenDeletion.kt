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
    internal fun resetForTest(){inFlight=false;resync=null}
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
    /** True while an instance-wide deleteToken call runs: registration waits. The durable [pending] flag is only retry intent, not a barrier. */
    val busy:Boolean get()=inFlight
    /** Test seam (null in production): replaces the process-owned resync run after every deletion outcome. */
    internal var resync:((Context)->Unit)?=null
    private fun attempt(context:Context) {
        synchronized(this){if(inFlight)return;inFlight=true}
        epoch++ // any FCM fetch or registration already in flight predates this deletion and is discarded
        try {
            val op=deleteTokenOp ?: {done:(Boolean)->Unit -> com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {t->done(t.isSuccessful)}}
            op {ok -> finish(context,ok)}
        } catch(_:Exception) { finish(context,false) }
    }
    /**
     * deleteToken is instance-wide, and a failed or unconfirmed delete does not prove the cached token is still valid,
     * so after EITHER outcome every cached token is dropped and only a freshly fetched one is registered. On success the
     * durable retry flag is cleared; on failure it stays so the next start tries again. Runs without any activity.
     */
    private fun finish(context:Context,ok:Boolean) {
        try {Session(context).let {it.fcmToken=null;it.fcmRegisteredToken=null}} catch(_:Exception) {}
        epoch++
        if(ok) {prefs(context).edit().remove("pending").apply();pending=false}
        inFlight=false
        if(ok) onCompleted?.invoke()
        try {(resync ?: {c:Context->PushRegistration.syncProcess(c)})(context)} catch(_:Exception) {}
    }
}

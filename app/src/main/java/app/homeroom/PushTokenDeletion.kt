package app.homeroom

import android.content.Context

/** Separate from cleared session preferences so a failed deletion survives sign-out. */
object PushTokenDeletion {
    @Volatile var pending=false
        private set
    /** Set by the visible activity: called on the main thread after a deletion completed. */
    @Volatile var onCompleted:(()->Unit)?=null
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
            com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {task ->
                if(task.isSuccessful) {
                    // A session enrolled again meanwhile may already hold a fresh token: only a signed-out session is wiped.
                    Session(context).let {if(!it.enrolled) {it.fcmToken=null;it.fcmRegisteredToken=null}}
                    prefs(context).edit().remove("pending").apply();pending=false
                    onCompleted?.invoke()
                }
                // Failure stays persisted for one attempt on the next app start.
            }
        } catch(_:Exception) { /* Keep pending for the next start. */ }
    }
}
package app.homeroom

import android.content.Context

/** Separate from cleared session preferences so a failed deletion survives sign-out. */
object PushTokenDeletion {
    @Volatile var pending=false
        private set
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
                    Session(context).apply {fcmToken=null;fcmRegisteredToken=null}
                    prefs(context).edit().remove("pending").apply();pending=false
                }
                // Failure stays persisted for one attempt on the next app start.
            }
        } catch(_:Exception) { /* Keep pending for the next start. */ }
    }
}
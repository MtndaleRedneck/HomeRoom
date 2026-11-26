package app.homeroom

import android.content.Context
import android.widget.ScrollView
import kotlinx.coroutines.launch

/** Client observations are not a claim that end-to-end delivery succeeded. */
class StatusScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private var checking=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {render()}
    private fun render() {
        body.removeAllViews()
        body.addView(text(context,"Connection",20f,C.text,true))
        body.addView(text(context,when { !host.online -> "Phone offline";!host.api.reachable -> "Last server request failed";else -> "No connection error recorded"},15f,C.text))
        body.addView(text(context,"Last successful server refresh: "+(host.api.lastSuccessfulRefresh ?: "None since opening"),14f,C.muted))
        val enabled=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        body.addView(text(context,"Alerts: "+when { !enabled -> "Blocked in Android settings";PushTokenDeletion.pending -> "Paused: token cleanup has not succeeded. Close and reopen to retry.";host.session.fcmRegisteredToken==null -> "Not registered";else -> "Registered with server. Delivery not confirmed."},14f,C.muted))
        body.addView(text(context,"Server features",20f,C.text,true))
        if(host.api.features.isEmpty())body.addView(text(context,"No feature report available. Refresh while connected.",14f,C.muted))
        host.api.features.toSortedMap().forEach {(name,on)->
            body.addView(text(context,name.replace('_',' ')+": "+if(on)"On" else "Off",14f,if(on)C.text else C.muted))
        }
        body.addView(button(context,if(checking)"Checking..." else "Refresh status",false) {
            if(!checking) {checking=true;render();screenScope.launch {
                try {host.api.renewOnOpen();snack(this@StatusScreen,"Server answered.")}
                catch(e:SessionEndedException) {host.onSessionEnded(e)}
                catch(_:Exception) {snack(this@StatusScreen,"Refresh not confirmed. Check your connection.")}
                finally {checking=false;render()}
            }}
        }.apply {isEnabled=!checking})
    }
}
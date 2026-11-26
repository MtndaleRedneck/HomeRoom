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
        val conn=sectionCard(context,"Connection")
        conn.addView(infoRow(context,"Server",when { !host.online -> "Phone offline";!host.api.reachable -> "Last server request failed";else -> "No connection error recorded"}))
        conn.addView(infoRow(context,"Last successful refresh",host.api.lastSuccessfulRefresh?.let {formatTime(it).ifBlank {it}} ?: "None since opening",C.muted))
        val enabled=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        conn.addView(infoRow(context,"Alerts",when { !enabled -> "Blocked in Android settings";PushTokenDeletion.pending -> "Paused: token cleanup has not succeeded. Close and reopen to retry.";host.session.fcmRegisteredToken==null -> "Not registered";else -> "Registered with server. Delivery not confirmed."},C.muted))
        body.addCard(conn)
        val features=sectionCard(context,"Server features")
        if(host.api.features.isEmpty())features.addView(text(context,"No feature report available. Refresh while connected.",13f,C.muted))
        host.api.features.toSortedMap().forEach {(name,on)->
            val line=android.widget.LinearLayout(context).apply {gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(40)}
            line.addView(text(context,name.replace('_',' ').replaceFirstChar {it.uppercase()},14f,C.text),android.widget.LinearLayout.LayoutParams(0,-2,1f))
            line.addView(text(context,if(on)"On" else "Off",14f,if(on)C.green else C.muted,true))
            features.addView(line)
        }
        body.addCard(features)
        body.addStacked(button(context,if(checking)"Checking..." else "Refresh status",false) {
            if(!checking) {checking=true;render();screenScope.launch {
                try {host.api.renewOnOpen();snack(this@StatusScreen,"Server answered.")}
                catch(e:SessionEndedException) {host.onSessionEnded(e)}
                catch(_:Exception) {snack(this@StatusScreen,"Refresh not confirmed. Check your connection.")}
                finally {checking=false;render()}
            }}
        }.apply {isEnabled=!checking})
    }
}
package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch

class SettingsScreen(context:Context):Screen(context) {
    private val col=column(context)
    private var devices=listOf<OwnerDevice>()
    private var devicesError=false
    private var devicesLoaded=false
    private var rooms=listOf<Room>()
    init {
        col.setPadding(dp(16),dp(8),dp(16),dp(110))
        addView(ScrollView(context).apply {addView(col)},LayoutParams(-1,-1))
    }
    override fun onShow() {
        rebuild()
        host.scope.launch {
            try {
                devices=host.api.sessions().objects().map {OwnerDevice.from(it)}
                rooms=host.cachedRooms().ifEmpty {host.api.roomRoster().rooms}
                devicesLoaded=true;devicesError=false;rebuild()
            } catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(_:Exception) {devicesError=true;rebuild()}
        }
    }
    private fun group(title:String):LinearLayout {
        col.addView(text(context,title,13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(20);bottomMargin=dp(8)})
        return column(context).apply {background=rounded(C.surface,dp(20).toFloat());clipToOutline=true;col.addView(this,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun row(card:LinearLayout,glyph:String,color:Int,title:String,subtitle:String,trailing:String?=null,click:(()->Unit)?=null) {
        if(card.childCount>0)card.addView(divider(context).apply {setBackgroundColor(0xff24303c.toInt())},LinearLayout.LayoutParams(-1,1).apply {leftMargin=dp(64)})
        val line=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(12),0);click?.let {action->setOnClickListener {action()}}}
        line.addView(text(context,glyph,18f,android.graphics.Color.WHITE,true).apply {gravity=Gravity.CENTER;background=rounded(color,dp(10).toFloat())},LinearLayout.LayoutParams(dp(32),dp(32)).apply {rightMargin=dp(14)})
        val copy=column(context);copy.addView(text(context,title,16f,C.text,true));copy.addView(text(context,subtitle,13.5f,C.muted).apply {maxLines=2})
        line.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        trailing?.let {label ->line.addView(text(context,label,13f,if(label=="Remove") C.danger else C.accent,true).apply {gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(C.surfaceAlt,dp(16).toFloat());click?.let {action->setOnClickListener {action()}}},LinearLayout.LayoutParams(-2,dp(32)))}
        card.addView(line,LinearLayout.LayoutParams(-1,dp(64)))
    }
    private fun rebuild() {
        col.removeAllViews()
        val owner=host.session.ownerName ?: "You"
        val ownerCard=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
        ownerCard.addView(text(context,owner.take(1),22f,C.bg,true).apply {gravity=Gravity.CENTER;background=rounded(C.owner,dp(22).toFloat())},LinearLayout.LayoutParams(dp(44),dp(44)).apply {rightMargin=dp(12)})
        val ownerCopy=column(context)
        ownerCopy.addView(text(context,owner+"  Owner",18f,C.text,true))
        val server=try {java.net.URI(host.session.serverUrl.orEmpty()).host ?: "HomeRoom"} catch(_:Exception) {"HomeRoom"}
        ownerCopy.addView(text(context,"Connected to "+server,13f,C.muted))
        ownerCard.addView(ownerCopy,LinearLayout.LayoutParams(0,-2,1f));col.addView(ownerCard,LinearLayout.LayoutParams(-1,dp(84)))
        val notifications=group("Notifications")
        val enabled=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        val push=when { !enabled -> "Off in Android settings";host.session.fcmRegisteredToken==null -> "Not set up";else -> "On · stacked per room" }
        row(notifications,"♧",0xffe5577a.toInt(),"Push notifications",push) {
            try {host.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))} catch(_:android.content.ActivityNotFoundException) {snack(this,"Notification settings aren't available.")}
        }
        val muted=rooms.filter {it.muted}
        row(notifications,"♧",0xff7a8b9c.toInt(),"Muted rooms",if(muted.isEmpty()) "None" else "${muted.size} rooms","›") {host.openMutedRooms()}
        val sessions=group("Signed-in devices")
        if(devicesError)row(sessions,"!",C.disabled,"Couldn't load devices","Tap to retry","Retry") {onShow()}
        else if(!devicesLoaded)row(sessions,"▯",C.surfaceAlt,"Loading devices","Checking signed-in devices")
        for(device in devices) {
            val current=device.isCurrent(host.session.sessionId)
            row(sessions,if(current) "▯" else "▱",if(current) C.accent else 0xff7b6cf0.toInt(),(if(current) "This phone" else device.label.ifBlank {"Device"}),if(current) "Active now" else device.lastUsed?.let {"Last used "+formatRoomTime(it)} ?: "Last use not recorded",if(current) null else "Remove",if(current) null else { {confirmRemove(device)} })
        }
        val app=group("App")
        val update=UpdateCheck.hasUpdate()
        row(app,"↑",C.amber,"HomeRoom "+BuildConfig.VERSION_NAME,if(update) "Update ${UpdateCheck.latestTag} available" else UpdateCheck.checkedAt?.let {"Checked "+formatBubbleTime(it)} ?: "Not checked yet",if(update) "Update" else "Check") {
            if(update && UpdateCheck.openLatest(host,host.scope)) return@row
            UpdateCheck.checkManually(host,host.scope,host.session) {message->snack(this,message);rebuild()}
        }
        // RECONSTRUCTED Nov 23, 2026: row recovered from the exact private12 release build.
        row(app,"›",C.accent,"Feedback & bugs","Report a problem or suggest something","›") {host.openPage("Feedback & bugs",FeedbackScreen(context))}
        val signout=text(context,"Sign out",16f,C.danger,true).apply {gravity=Gravity.CENTER;background=rounded(C.surface,dp(20).toFloat());setOnClickListener {confirmSignOut()}}
        col.addView(signout,LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(24)})
        // NOTE: an earlier reconstruction called a backdrop refresh here; no
        // backdrop exists in the exact release build evidence, so it was removed.
    }
    private fun confirmRemove(device:OwnerDevice) {
        AlertDialog.Builder(context).setTitle("Sign out ${device.label}?").setNegativeButton("Cancel",null).setPositiveButton("Sign out") {_,_->
            host.scope.launch {
                try {host.api.revokeSession(device.id);onShow()}
                catch(e:SessionEndedException) {host.onSessionEnded(e)}
                catch(_:Exception) {snack(this@SettingsScreen,"Couldn't sign out that device. Try again.")}
            }
        }.show()
    }
    private fun confirmSignOut() {
        AlertDialog.Builder(context).setTitle("Sign out of HomeRoom on this phone?").setNegativeButton("Cancel",null).setPositiveButton("Sign out") {_,_->
            host.scope.launch {
                host.session.fcmRegisteredToken?.let {token->try {host.api.unregisterPushToken(token)}catch(_:Exception) {}}
                try {host.api.logout()}catch(_:Exception) {}
                com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken()
                host.session.clear();host.onSignedOut()
            }
        }.show()
    }
}
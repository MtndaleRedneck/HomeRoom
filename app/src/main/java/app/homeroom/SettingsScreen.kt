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
        // Restored from d5.java: the Privacy section row opens App lock and
        // privacy (the screen and gate machinery already exist; only the
        // entry was missing from Settings).
        val privacy=group("Privacy")
        row(privacy,"▣",C.violet,"App lock and privacy","Fingerprint or PIN, lock screen and recent apps","›") {host.openPage("App lock and privacy",PrivacyScreen(context))}
        val app=group("App")
        // private12 parity (K0/Y4:215 "Unsent attachments", K0/A3:61 "Outgoing
        // messages" settings rows): both target screens exist but had no entry.
        row(app,"=",C.violet,"Outgoing messages","Queued sends still waiting to go out","›") {host.openPage("Outgoing messages",OutboxScreen(context))}
        row(app,"=",C.violet,"Unsent attachments","Staged uploads you can resume or remove","›") {host.openPage("Unsent attachments",StagedUploadsScreen(context))}
        // RECONSTRUCTED Nov 23, 2026: row recovered from the exact private12 release build.
        row(app,"›",C.accent,"Feedback & bugs","Report a problem or suggest something","›") {host.openPage("Feedback & bugs",FeedbackScreen(context))}
        // Locked Updates card, restored to the exact private12 arrangement
        // (d5.java): rounded surface card with a bold "Updates" header, a
        // state-machine button (#1475B3, rounded 12dp, disabled at 60% alpha
        // while busy), an optional centered status line with the original
        // strings, a "What's new" section when an update carries notes, and
        // the version line, at the bottom above Sign out. private12 has no
        // 20-second up-to-date revert timer anywhere in the decompile; the
        // status stays until the next check or rebuild.
        val update=UpdateCheck.hasUpdate()
        val updatesCard=column(context).apply {
            background=rounded(C.surface,dp(20).toFloat())
            setPadding(dp(16),dp(16),dp(16),dp(16))
        }
        updatesCard.addView(text(context,"Updates",17f,C.text,true))
        val busy=UpdateCheck.state==UpdateCheck.State.CHECKING || UpdateCheck.state==UpdateCheck.State.DOWNLOADING
        val btnLabel=when {
            UpdateCheck.state==UpdateCheck.State.CHECKING -> "Checking..."
            UpdateCheck.state==UpdateCheck.State.DOWNLOADING -> "Downloading... ${UpdateCheck.progress}%"
            update -> "Update ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""}"
            else -> "Check for updates"
        }
        val checkBtn=text(context,btnLabel,16f,android.graphics.Color.WHITE,true).apply {
            gravity=Gravity.CENTER
            background=rounded(android.graphics.Color.parseColor("#1475B3"),dp(12).toFloat())
            isEnabled=!busy
            alpha=if(isEnabled) 1.0f else 0.6f
            setOnClickListener {
                if(update && UpdateCheck.openLatest(host,host.scope)) return@setOnClickListener
                UpdateCheck.checkManually(host,host.scope,host.session) {_->rebuild()}
            }
        }
        updatesCard.addView(checkBtn,LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(12)})
        val status=when {
            update -> "Version ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""} is available."
            UpdateCheck.state==UpdateCheck.State.UP_TO_DATE -> "You're up to date."
            UpdateCheck.state==UpdateCheck.State.CHECK_FAILED -> "Couldn't check. Try again."
            UpdateCheck.state==UpdateCheck.State.DOWNLOAD_FAILED -> "The download didn't finish. Try again."
            UpdateCheck.state==UpdateCheck.State.NEEDS_PERMISSION -> "Allow installs from HomeRoom in Android's screen, then tap Install again."
            else -> ""
        }
        if(status.isNotEmpty()) {
            updatesCard.addView(text(context,status,13.5f,C.muted).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        }
        val notes=UpdateCheck.latestNotes
        if(update && !notes.isNullOrBlank()) {
            updatesCard.addView(text(context,"What's new in ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""}",14f,C.text,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
            updatesCard.addView(text(context,notes,13.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
        }
        updatesCard.addView(text(context,"Version "+BuildConfig.VERSION_NAME,13.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        col.addView(updatesCard,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(20)})
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
        AlertDialog.Builder(context).setTitle("Sign out of HomeRoom on this phone?").setMessage("Signing out erases this phone's saved sign-in and any unsent staged attachments.").setNegativeButton("Cancel",null).setPositiveButton("Sign out") {_,_->
            host.scope.launch {
                host.session.fcmRegisteredToken?.let {token->try {host.api.unregisterPushToken(token)}catch(_:Exception) {}}
                try {host.api.logout()}catch(_:Exception) {}
                com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken()
                host.session.clear();host.onSignedOut()
            }
        }.show()
    }
}
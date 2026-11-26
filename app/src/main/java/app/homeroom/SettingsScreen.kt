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
    private fun group(title:String?):LinearLayout {
        val card=sectionCard(context,title)
        card.tag=if(title!=null) 1 else 0
        col.addView(card,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        return card
    }
    private fun divide(card:LinearLayout) {
        val titled=(card.tag as? Int) ?: 0
        if(card.childCount>titled)card.addView(divider(context).apply {setBackgroundColor(0xff24303c.toInt())},LinearLayout.LayoutParams(-1,1))
    }
    private fun row(card:LinearLayout,title:String,subtitle:String,trailing:String?=null,click:(()->Unit)?=null) {
        divide(card)
        val line=navRow(context,title,subtitle,chevron=trailing=="›",onClick=click)
        if(trailing!=null && trailing!="›") {
            line.addView(text(context,trailing,12.5f,if(trailing=="Remove") C.danger else C.accent,true).apply {gravity=Gravity.CENTER;minHeight=dp(48);setPadding(dp(12),0,dp(4),0)})
        }
        card.addView(line,LinearLayout.LayoutParams(-1,-2))
    }
    private fun rebuild() {
        col.removeAllViews()
        val owner=host.session.ownerName ?: "You"
        val ownerCard=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;background=rounded(C.surface,dp(16).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
        ownerCard.addView(text(context,owner.take(1),22f,C.bg,true).apply {gravity=Gravity.CENTER;background=rounded(C.owner,dp(22).toFloat())},LinearLayout.LayoutParams(dp(44),dp(44)).apply {rightMargin=dp(12)})
        val ownerCopy=column(context)
        ownerCopy.addView(text(context,owner+"  Owner",18f,C.text,true))
        val server=try {java.net.URI(host.session.serverUrl.orEmpty()).host ?: "HomeRoom"} catch(_:Exception) {"HomeRoom"}
        ownerCopy.addView(text(context,"Connected to "+server,13f,C.muted))
        ownerCard.addView(ownerCopy,LinearLayout.LayoutParams(0,-2,1f));col.addView(ownerCard,LinearLayout.LayoutParams(-1,dp(84)))
        val notifications=group("Notifications")
        val enabled=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        val push=when { !enabled -> "Off in Android settings";host.session.fcmRegisteredToken==null -> "Not set up";else -> "On · stacked per room" }
        row(notifications,"Push notifications",push) {
            try {host.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))} catch(_:android.content.ActivityNotFoundException) {snack(this,"Notification settings aren't available.")}
        }
        val muted=rooms.filter {it.muted}
        row(notifications,"Muted rooms",if(muted.isEmpty()) "None" else "${muted.size} rooms","›") {host.openMutedRooms()}
        val sessions=group("Signed-in devices")
        if(devicesError)row(sessions,"Couldn't load devices","Tap to retry","Retry") {onShow()}
        else if(!devicesLoaded)row(sessions,"Loading devices","Checking signed-in devices")
        for(device in devices) {
            val current=device.isCurrent(host.session.sessionId)
            row(sessions,(if(current) "This phone" else device.label.ifBlank {"Device"}),if(current) "Active now" else device.lastUsed?.let {"Last used "+formatRoomTime(it)} ?: "Last use not recorded",if(current) null else "Remove",if(current) null else { {confirmRemove(device)} })
        }
        // Restored from d5.java: the Privacy section row opens App lock and
        // privacy (the screen and gate machinery already exist; only the
        // entry was missing from Settings).
        val privacy=group("Privacy")
        row(privacy,"App lock and privacy","Fingerprint or PIN, lock screen and recent apps","›") {host.openPage("App lock and privacy",PrivacyScreen(context))}
        // 1.7: shared Feel section (BingeMote wording "Vibrate on press");
        // gates every press haptic in the app.
        val feel=group("Feel")
        toggleRow(feel,"Vibrate on press","A small buzz when buttons and tabs are tapped",Haptics.on(context)) {on->Haptics.set(context,on)}
        val app=group("App")
        // private12 parity, settings rows verified against the release's own
        // targets (K0/Y4 handlers): "Activity" -> D1(true), "Needs you" ->
        // D1(false) [our DashboardScreen(activityLog) carries the same boolean];
        // "AI team" -> w5 [strings match TeamScreen]; "AI guide" -> T0 mode 1
        // [GuideScreen]; "Connection and features" -> u5 [strings match
        // StatusScreen]; "Unsent attachments" -> T0 mode 4 (K0/Y4:215)
        // [StagedUploadsScreen]; "Outgoing messages" (K0/A3:61) [OutboxScreen].
        row(app,"Needs you","Decisions and requests waiting on you","›") {host.openPage("Needs you",DashboardScreen(context,false))}
        row(app,"Activity","Recent activity across your rooms","›") {host.openPage("Activity",DashboardScreen(context,true))}
        row(app,"AI team","What your AIs are working on","›") {host.openPage("AI team",TeamScreen(context))}
        row(app,"AI guide","The guide your AIs follow","›") {host.openPage("AI guide",GuideScreen(context))}
        row(app,"Connection and features","Server connection, alerts and feature report","›") {host.openPage("Connection and features",StatusScreen(context))}
        row(app,"Outgoing messages","Queued sends still waiting to go out","›") {host.openPage("Outgoing messages",OutboxScreen(context))}
        row(app,"Unsent attachments","Staged uploads you can resume or remove","›") {host.openPage("Unsent attachments",StagedUploadsScreen(context))}
        // Release rows "Add room" and "Archived rooms" have no reconstructed
        // flow and are intentionally not offered (tracked gaps, not wired).
        // 1.7 (Ezzy, Nov 25: bring the Settings list in line with his other
        // apps): the shared bottom arrangement is Sign out, then a Feedback &
        // bugs card, then the Updates card ALWAYS last (his locked rule; the
        // old order had Sign out below Updates, a known exception he asked to
        // fix on a later update).
        val signout=text(context,"Sign out",14f,C.danger,true).apply {gravity=Gravity.CENTER;background=rounded(C.surface,dp(16).toFloat());pressIn();setOnClickListener {confirmSignOut()}}
        col.addView(signout,LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(14)})
        val feedback=group("Feedback & bugs")
        row(feedback,"Send feedback, report a bug, or suggest a feature","","›") {host.openPage("Feedback & bugs",FeedbackScreen(context))}
        // Locked Updates card (shared cross-app design): rounded surface card
        // with a bold "Updates" header, a state-machine button (accent,
        // rounded 12dp, disabled at 60% alpha while busy), an optional centered
        // status line (NOT used for up-to-date), a "What's new" section when an
        // update carries notes, and a bottom row with the version at the left
        // and "You're up to date." at the right, reverting after 5 seconds.
        // Always the LAST card in Settings.
        val update=UpdateCheck.hasUpdate()
        val updatesCard=sectionCard(context)
        val busy=UpdateCheck.state==UpdateCheck.State.CHECKING || UpdateCheck.state==UpdateCheck.State.DOWNLOADING
        val btnLabel=when {
            UpdateCheck.state==UpdateCheck.State.CHECKING -> "Checking..."
            UpdateCheck.state==UpdateCheck.State.DOWNLOADING -> "Downloading... ${UpdateCheck.progress}%"
            update -> "Update ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""}"
            else -> "Check for updates"
        }
        val checkBtn=text(context,btnLabel,14f,android.graphics.Color.parseColor("#1B1B1E"),true).apply {
            gravity=Gravity.CENTER
            background=rounded(C.accent,dp(14).toFloat())
            isEnabled=!busy
            alpha=if(isEnabled) 1.0f else 0.6f
            pressIn()
            setOnClickListener {
                if(update && UpdateCheck.openLatest(host,host.scope)) return@setOnClickListener
                UpdateCheck.checkManually(host,host.scope,host.session) {_->rebuild()}
            }
        }
        updatesCard.addView(checkBtn,LinearLayout.LayoutParams(-1,dp(48)))
        val status=when {
            update -> "Version ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""} is available."
            UpdateCheck.state==UpdateCheck.State.CHECK_FAILED -> "Couldn't check. Try again."
            UpdateCheck.state==UpdateCheck.State.DOWNLOAD_FAILED -> "The download didn't finish. Try again."
            UpdateCheck.state==UpdateCheck.State.NEEDS_PERMISSION -> "Allow installs from HomeRoom in Android's screen, then tap Install again."
            else -> ""
        }
        // "You're up to date." is NOT a status line here: per the locked shared
        // design it sits on the version row, level with the version, and
        // reverts after 5 seconds.
        if(status.isNotEmpty()) {
            updatesCard.addView(text(context,status,12.5f,C.muted).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        }
        val notes=UpdateCheck.latestNotes
        if(update && !notes.isNullOrBlank()) {
            updatesCard.addView(text(context,"What's new in ${UpdateCheck.latestTag?.let {UpdateCheck.displayVersion(it)} ?: ""}",14f,C.text,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
            updatesCard.addView(text(context,notes,12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
        }
        val versionRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        versionRow.addView(text(context,"Version "+BuildConfig.VERSION_NAME,12f,C.muted),LinearLayout.LayoutParams(0,-2,1f))
        if(UpdateCheck.state==UpdateCheck.State.UP_TO_DATE) {
            versionRow.addView(text(context,"You're up to date.",12f,C.muted))
            // The 5-second rule, made visible: UpdateCheck flips back to IDLE
            // on its own timer (which only refreshes the nav dot), so repaint
            // just after to actually drop the line while the screen stays open.
            // Bounded re-checks cover the timer landing a beat late; a fresh
            // check rebuilds through its own callback and ends the loop.
            var checks=0
            val revertCheck=object:Runnable { override fun run() {
                if(!isAttachedToWindow) return
                if(UpdateCheck.state!=UpdateCheck.State.UP_TO_DATE) {rebuild();return}
                if(checks++<6) postDelayed(this,250)
            } }
            postDelayed({ if(isAttachedToWindow) revertCheck.run() },5100)
        }
        updatesCard.addView(versionRow,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        col.addView(updatesCard,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        // NOTE: an earlier reconstruction called a backdrop refresh here; no
        // backdrop exists in the exact release build evidence, so it was removed.
    }
    private fun toggleRow(card:LinearLayout,title:String,subtitle:String,on:Boolean,onToggle:(Boolean)->Unit) {
        divide(card)
        val line=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48);setPadding(0,dp(4),0,dp(4))}
        val copy=column(context);copy.addView(text(context,title,15f,C.text,true));copy.addView(text(context,subtitle,12.5f,C.muted).apply {maxLines=2})
        line.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        val trackOn=0x59fc8613.toInt()
        val sw=android.widget.Switch(context).apply {
            isChecked=on
            thumbTintList=android.content.res.ColorStateList.valueOf(if(on) C.accent else C.disabled)
            trackTintList=android.content.res.ColorStateList.valueOf(if(on) trackOn else C.surfaceAlt)
            setOnCheckedChangeListener {_,checked->
                thumbTintList=android.content.res.ColorStateList.valueOf(if(checked) C.accent else C.disabled)
                trackTintList=android.content.res.ColorStateList.valueOf(if(checked) trackOn else C.surfaceAlt)
                onToggle(checked)
                // After persisting: turning ON buzzes, turning OFF stays silent.
                tapHaptic(this)
            }
        }
        line.addView(sw,LinearLayout.LayoutParams(-2,-2))
        line.setOnClickListener {sw.toggle()}
        // Press-in WITHOUT the down-haptic: the switch's own change callback
        // buzzes, so every activation (row tap or direct switch tap) buzzes
        // exactly once.
        line.pressIn(haptic=false)
        card.addView(line,LinearLayout.LayoutParams(-1,-2))
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
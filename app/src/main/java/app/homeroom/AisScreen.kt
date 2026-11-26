package app.homeroom

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings > AIs: add an AI with a join message, name it, give it a picture, review its key, or remove it. */
class AisScreen(context:Context):Screen(context) {
    private val body=column(context)
    private var loading=false
    private var ais=listOf<OwnerAi>()
    private var joinRooms=listOf<String>()
    private var busy=false
    /** Join messages live only here: the server never shows a code twice. */
    private class Shown(val invite:JoinInvite,val receivedAt:Long)
    private val shown=mutableMapOf<String,Shown>()
    private val countdowns=mutableMapOf<String,android.widget.TextView>()
    private var ticker:Job?=null
    init {body.setPadding(dp(16),dp(12),dp(16),dp(24));addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    internal fun setAis(list:List<OwnerAi>,rooms:List<String> = emptyList()) {ais=list;joinRooms=rooms;render()}
    internal fun showInvite(invite:JoinInvite,receivedAt:Long) {shown[invite.actor]=Shown(invite,receivedAt)}
    private fun load() {
        if(loading)return
        loading=true
        screenScope.launch {
            try {val (list,rooms)=host.api.aisAndJoinRooms();ais=list;joinRooms=rooms;render()}
            catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(_:Exception) {body.removeAllViews();body.addView(text(context,"Couldn't load your AIs. Tap to retry.",15f,C.muted).apply {setOnClickListener {load()}})}
            finally {loading=false}
        }
    }
    private fun render() {
        body.removeAllViews();countdowns.clear()
        body.addStacked(button(context,"+ Add AI",true) {addDialog()},LinearLayout.LayoutParams(-1,dp(48)))
        if(ais.isEmpty()) body.addView(text(context,"No AIs linked yet.",15f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
        ais.forEach {card(it)}
        body.addView(text(context,"A key is how an AI proves it is really that AI. When an AI connects, you check its key here before it can do anything.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
        body.addStacked(button(context,"Review AI keys",false) {host.openPage("AI keys",AiKeysScreen(context))},LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(8)})
        startTicker()
    }
    private fun startTicker() {
        if(countdowns.isEmpty()) {ticker?.cancel();ticker=null;return}
        if(ticker?.isActive==true)return
        ticker=screenScope.launch {
            while(countdowns.isNotEmpty()) {
                delay(1000)
                var expired=false
                for((actor,view) in countdowns) {
                    val s=shown[actor] ?: continue
                    val left=s.invite.secondsLeft(System.currentTimeMillis(),s.receivedAt)
                    if(left<=0) expired=true else view.text=countdownText(left)
                }
                if(expired) {render();return@launch}
            }
        }
    }
    private fun countdownText(seconds:Long)="Expires in "+(seconds/60)+":"+"%02d".format(seconds%60)
    private fun statusText(a:OwnerAi)=when(a.keyStatus) {"active" -> "Linked";"pending" -> "Key waiting for your approval";else -> if(a.join?.status=="unused") "Waiting for the AI to join" else "Not connected yet"}
    private fun when_(a:String?)=a?.let {formatRoomTime(it)}
    private fun card(a:OwnerAi) {
        val c=column(context).apply {background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
        val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val pic=Avatars.view(context,screenScope,host.api,a.displayName,personColor(a.actor),40,a.avatar).apply {
            contentDescription="Change picture for "+a.displayName;setOnClickListener {pictureDialog(a)}}
        head.addView(pic,LinearLayout.LayoutParams(dp(40),dp(40)).apply {rightMargin=dp(12)})
        head.addView(text(context,a.displayName,17f,C.text,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text(context,"Rename",13f,C.accentText,true).apply {setPadding(dp(8),dp(8),dp(0),dp(8));setOnClickListener {renameDialog(a)}})
        c.addView(head)
        c.addView(text(context,statusText(a),13.5f,when(a.keyStatus) {"active" -> C.green;"pending" -> C.amber;else -> C.muted}),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        if(a.keyStatus=="active") {
            c.addView(text(context,"Last check-in: "+(when_(a.lastCheckInAt) ?: "none yet"),12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
            c.addView(text(context,"Last read the rules: "+(when_(a.lastRulesReadAt) ?: "not yet"),12.5f,C.muted))
        }
        if(a.keyStatus=="pending") c.addView(button(context,"Review its key",true) {host.openPage("AI keys",AiKeysScreen(context))},LinearLayout.LayoutParams(-1,dp(40)).apply {topMargin=dp(10)})
        if(a.keyStatus!="active" && a.keyStatus!="pending") joinPanel(c,a)
        c.addView(text(context,"Remove",13.5f,C.danger,true).apply {setPadding(0,dp(12),dp(12),dp(4));setOnClickListener {removeDialog(a)}})
        body.addView(c,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
    }
    private fun joinPanel(c:LinearLayout,a:OwnerAi) {
        val s=shown[a.actor]?.takeIf {it.invite.secondsLeft(System.currentTimeMillis(),it.receivedAt)>0}
        val live=a.join?.status=="unused"
        if(s!=null) {
            c.addView(text(context,"Works with AIs that can run code and privately keep a signing key.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            val timer=text(context,countdownText(s.invite.secondsLeft(System.currentTimeMillis(),s.receivedAt)),14f,C.text,true)
            countdowns[a.actor]=timer
            c.addView(timer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            c.addView(button(context,"Copy join message",true) {copyMessage(s.invite)},LinearLayout.LayoutParams(-1,dp(44)).apply {topMargin=dp(8)})
            c.addView(text(context,"Paste it into that AI's own chat. It works once and expires in 15 minutes.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        } else {
            c.addView(text(context,if(live) "A join message was made earlier. It can't be shown again, so make a new one." else "Make a join message to connect this AI.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        }
        val row=LinearLayout(context)
        row.addView(button(context,if(s!=null || live) "Regenerate" else "Get join message",s==null && !live) {issue(a.actor)}.apply {minHeight=0;minimumHeight=0},LinearLayout.LayoutParams(0,dp(40),1f))
        if(live || s!=null) row.addView(button(context,"Cancel",false) {cancelJoin(a)}.apply {minHeight=0;minimumHeight=0},LinearLayout.LayoutParams(0,dp(40),1f).apply {leftMargin=dp(8)})
        c.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
    }
    private fun copyMessage(invite:JoinInvite) {
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip=ClipData.newPlainText("HomeRoom join message",invite.joinMessage)
        if(android.os.Build.VERSION.SDK_INT>=33) clip.description.extras=android.os.PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
        clipboard.setPrimaryClip(clip)
        snack(this,"Copied. Paste it to your AI in its own chat.")
    }
    private fun addDialog() {
        val builder=AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog)
        val form=column(context).apply {setPadding(dp(20),dp(8),dp(20),0)}
        val name=input(builder.context,"Name for this AI")
        form.addView(name)
        form.addView(text(context,"Any name you will recognize. Next you get a message to paste to the AI.",12.5f,C.muted).apply {setPadding(0,dp(6),0,0)})
        builder.setTitle("Add AI").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Add") {_,_->create(name.text.toString().trim())}.showTracked()
    }
    private fun renameDialog(a:OwnerAi) {
        val builder=AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog)
        val name=input(builder.context,"Name").apply {setText(a.displayName);setPadding(dp(20),dp(8),dp(20),dp(8))}
        builder.setTitle("Rename AI").setView(name).setNegativeButton("Cancel",null)
            .setPositiveButton("Save") {_,_->rename(a,name.text.toString().trim())}.showTracked()
    }
    private fun pictureDialog(a:OwnerAi) {
        val items=if(a.avatar!=null) arrayOf("Choose a picture","Remove picture") else arrayOf("Choose a picture")
        AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle("Picture for "+a.displayName).setItems(items) {_,which ->
            if(which==0) host.pickImage {uri -> if(uri!=null) setPicture(a,uri)} else setPicture(a,null)
        }.setNegativeButton("Cancel",null).showTracked()
    }
    private fun removeDialog(a:OwnerAi) {
        AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle("Remove "+a.displayName+"?")
            .setMessage(a.displayName+" loses access right away and leaves your rooms. Past messages and rules stay as they are. To bring it back you would add it again as a new AI.")
            .setNegativeButton("Cancel",null).setPositiveButton("Remove") {_,_->unlink(a)}.showTracked()
    }
    private fun create(name:String) {
        if(name.isEmpty()||busy) {if(name.isEmpty())snack(this,"Give the AI a name.");return}
        busy=true
        screenScope.launch {
            var actor:String?=null
            try {actor=host.api.createAi(name,"other").actor}
            catch(e:SessionEndedException) {host.onSessionEnded(e);busy=false;return@launch}
            catch(e:ApiException) {snack(this@AisScreen,if(e.code=="bad_name"||e.status==400) "That name can't be used." else if(e.status==429) "Slow down. Try again in a moment." else "Couldn't add the AI.")}
            // Ambiguous failure: never resend, reload and look.
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm. Check the list before adding again.")}
            busy=false
            if(actor!=null) issue(actor) else {loading=false;load()}
        }
    }
    private fun issue(actor:String) {
        if(busy)return
        busy=true
        screenScope.launch {
            try {
                // Refresh first so the join room is the one the server allows right now.
                val (list,rooms)=host.api.aisAndJoinRooms();ais=list;joinRooms=rooms
                if(rooms.isEmpty()) {snack(this@AisScreen,"Can't start a join right now. This account has no room that allows it.");render();return@launch}
                val invite=host.api.createJoinCode(actor,rooms.take(1))
                shown[actor]=Shown(invite,System.currentTimeMillis())
                val refreshed=host.api.aisAndJoinRooms();ais=refreshed.first;joinRooms=refreshed.second;render()
            }
            catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(e:ApiException) {
                snack(this@AisScreen,when {
                    e.code=="actor_enrollment_pending" -> "This AI already sent a key. Open Review AI keys, Deny it, then make a new join message."
                    e.code=="actor_already_active" -> "This AI is already linked."
                    e.status==429 -> "Too many tries. Wait a bit and try again."
                    else -> "Couldn't make a join message."
                });loading=false;load()
            }
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm. Tap Regenerate to make a new join message.");loading=false;load()}
            finally {busy=false}
        }
    }
    private fun cancelJoin(a:OwnerAi) {
        val joinId=shown[a.actor]?.invite?.joinId ?: a.join?.joinId ?: return
        if(busy)return
        busy=true
        screenScope.launch {
            try {host.api.revokeJoinCode(a.actor,joinId);shown.remove(a.actor);snack(this@AisScreen,"Join message cancelled.")}
            catch(e:SessionEndedException) {host.onSessionEnded(e);busy=false;return@launch}
            catch(e:ApiException) {snack(this@AisScreen,if(e.code=="key_already_approved") "That key was already approved. Remove the AI to cut off its access." else "Couldn't cancel.")}
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm. Check the list.")}
            busy=false;loading=false;load()
        }
    }
    private fun rename(a:OwnerAi,name:String) {
        if(name.isEmpty()||busy||name==a.displayName)return
        busy=true
        screenScope.launch {
            try {host.api.renameAi(a.actor,name)}
            catch(e:SessionEndedException) {host.onSessionEnded(e);busy=false;return@launch}
            catch(e:ApiException) {snack(this@AisScreen,if(e.status==400) "That name can't be used." else "Couldn't rename.")}
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm the rename.")}
            busy=false;loading=false;load()
        }
    }
    private fun setPicture(a:OwnerAi,uri:android.net.Uri?) {
        if(busy)return
        busy=true
        screenScope.launch {
            try {
                val upload=if(uri==null) null else (Avatars.prepare(context,uri) ?: run {snack(this@AisScreen,"That picture can't be used. Try a different one.");busy=false;return@launch})
                host.api.setAiAvatar(a.actor,upload)
            }
            catch(e:SessionEndedException) {host.onSessionEnded(e);busy=false;return@launch}
            catch(e:ApiException) {snack(this@AisScreen,if(e.code=="bad_avatar") "That picture can't be used. Try a different one." else "Couldn't save the picture.")}
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm the picture.")}
            busy=false;loading=false;load()
        }
    }
    private fun unlink(a:OwnerAi) {
        if(busy)return
        busy=true
        screenScope.launch {
            try {host.api.unlinkAi(a.actor);shown.remove(a.actor);snack(this@AisScreen,a.displayName+" removed. Its access is revoked and past messages stay.")}
            catch(e:SessionEndedException) {host.onSessionEnded(e);busy=false;return@launch}
            catch(e:ApiException) {if(e.status==404) snack(this@AisScreen,"Already removed.") else snack(this@AisScreen,"Couldn't remove it.")}
            catch(_:Exception) {snack(this@AisScreen,"Couldn't confirm. Check the list.")}
            busy=false;loading=false;load()
        }
    }
}
// RECONSTRUCTED Nov 23, 2026: not original source. Behavior recovered from
// the exact private12 release build (APK sha256
// f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d).
// Requires fresh review and tests before any release or equivalence claim.
package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings > AIs > AI keys: review each connecting AI's key, compare its fingerprint, approve or deny. */
class AiKeysScreen(context:Context):Screen(context) {
    private val body=column(context)
    private var busy=false
    private var keys=listOf<AiKey>()
    /** keyId -> (compare-state, typed suffix); typed text resets when the key's data changes. */
    private val typed=linkedMapOf<String,Pair<String,String>>()
    init {body.setPadding(dp(16),dp(12),dp(16),dp(24));addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    internal fun setKeys(list:List<AiKey>) {keys=list;render()}
    private fun load() {
        if(busy)return
        busy=true
        screenScope.launch {
            try {setKeys(host.api.aiKeys())}
            catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(e:ApiException) {loadError(if(e.code=="not_member") "This account isn't allowed to see AI keys." else "Couldn't load AI keys. Tap to retry.")}
            catch(_:Exception) {loadError("Couldn't load AI keys. Tap to retry.")}
            finally {busy=false}
        }
    }
    private fun loadError(msg:String) {
        body.removeAllViews()
        body.addView(text(context,msg,15f,C.muted).apply {setOnClickListener {load()}})
    }
    private fun render() {
        body.removeAllViews()
        body.addView(text(context,"A key is how an AI proves it is really that AI. When one of your AIs connects, its key shows up here. Check the name and the rooms, and approve it only if you set that AI up yourself.",13.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(16)})
        if(keys.isEmpty()) {body.addView(text(context,"No AI keys waiting.",15f,C.muted));return}
        val pending=keys.filter {it.status=="pending"}
        val decided=keys.filter {it.status!="pending"}
        if(pending.isNotEmpty()) body.addView(text(context,"Needs your yes · ${pending.size}",13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        pending.forEach {card(it)}
        if(decided.isNotEmpty()) body.addView(text(context,"Decided",13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16);bottomMargin=dp(8)})
        decided.forEach {card(it)}
    }
    private fun line(parent:LinearLayout,label:String,value:CharSequence) {
        parent.addView(text(context,label,12f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        parent.addView(text(context,value,14f,C.text))
    }
    private fun compareState(key:AiKey):String =
        listOf(key.keyId,key.actor,key.publicKeySha256,key.rooms.joinToString(","),key.replaces?:"").joinToString("|")
    private fun updateApprove(key:AiKey,btn:Button,typedValue:String) {
        val ok=!key.suspectPending && typedValue.trim().lowercase(Locale.ROOT)==key.suffix()
        btn.isEnabled=ok;btn.alpha=if(ok) 1f else 0.5f
    }
    private fun card(key:AiKey) {
        val c=column(context).apply {background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
        val head=LinearLayout(context).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        head.addView(text(context,key.name,17f,C.text,true),LinearLayout.LayoutParams(0,-2,1f))
        val statusColor=when(key.status) {"active"->C.teal;"pending"->C.accentText;else->C.muted}
        head.addView(text(context,key.status.replaceFirstChar {if(it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()},12.5f,statusColor,true))
        c.addView(head)
        line(c,"AI ID",key.actor)
        line(c,"Key ID",key.keyId)
        line(c,"Fingerprint (SHA-256)",key.publicKeySha256.chunked(16).joinToString("\n") {it.chunked(4).joinToString(" ")})
        line(c,"Rooms",key.rooms.joinToString("\n"))
        line(c,"Replaces",key.replaces?:"Nothing (new key)")
        if(key.status=="pending") {
            val state=compareState(key)
            if(typed[key.keyId]?.first!=state) typed[key.keyId]=state to ""
            if(key.suspectPending) c.addView(text(context,"Marked suspicious: the AI said its join message was already used. Deny this key.",13.5f,C.danger,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
            c.addView(text(context,"If your AI did not just show you this fingerprint, tap Deny.",13.5f,C.accentText,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
            c.addView(text(context,"Compare the whole fingerprint with what the AI showed you in its own chat. Then type the last ${key.suffixLength} characters here.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
            val approveBtn=button(context,"Approve",true) {confirmApprove(key)}
            val edit=input(context,"Last ${key.suffixLength} characters")
            edit.background=rounded(C.surfaceAlt,dp(12).toFloat())
            edit.setPadding(dp(12),dp(10),dp(12),dp(10))
            edit.setText(typed[key.keyId]?.second?:"")
            // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS
            edit.inputType=524433
            edit.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
                override fun afterTextChanged(s:Editable) {
                    typed[key.keyId]=state to s.toString()
                    updateApprove(key,approveBtn,s.toString())
                }
            })
            c.addView(edit,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            updateApprove(key,approveBtn,typed[key.keyId]?.second?:"")
            val row=LinearLayout(context)
            row.addView(button(context,"Deny",false) {confirmDeny(key)},LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(approveBtn,LinearLayout.LayoutParams(0,dp(44),1f).apply {leftMargin=dp(8)})
            c.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        }
        body.addView(c,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
    }
    private fun confirmApprove(key:AiKey) {
        AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog)
            .setTitle("Approve this key for ${key.name}?")
            .setMessage("Only approve this if the AI itself showed you this same fingerprint.\n\nKey ${key.keyId}\nEnds in ${key.suffix()}\nRooms: ${key.rooms.size}")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Approve") {_,_ -> approve(key)}
            .showTracked()
    }
    private fun confirmDeny(key:AiKey) {
        AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog)
            .setTitle("Deny this key?")
            .setMessage("${key.name} can't use this key. To connect it again, make a new join message.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Deny") {_,_ -> deny(key)}
            .showTracked()
    }
    private fun approve(key:AiKey) {
        screenScope.launch {
            try {
                host.api.approveAiKey(key,typed[key.keyId]?.second?:"")
                typed.remove(key.keyId)
                snack(this@AiKeysScreen,"Key approved.")
            } catch(e:ApiException) {
                snack(this@AiKeysScreen,when(e.code) {
                    "rotation_source_changed"->"The key it replaces changed. Check and try again."
                    "key_approval_conflict"->"That key changed. Check the new details and try again."
                    "fingerprint_confirmation_required"->"The ending you typed doesn't match. Check it against the AI's message."
                    "key_not_pending"->"That key was already decided."
                    "not_member"->"This account can't approve that key."
                    else->"Couldn't approve. Try again."
                })
            } catch(e:SessionEndedException) {host.onSessionEnded(e);return@launch
            } catch(_:Exception) {snack(this@AiKeysScreen,"Couldn't confirm. Check the list before trying again.")}
            busy=false;load()
        }
    }
    private fun deny(key:AiKey) {
        screenScope.launch {
            try {
                host.api.denyAiKey(key.keyId)
                snack(this@AiKeysScreen,"Key denied.")
            } catch(e:ApiException) {
                snack(this@AiKeysScreen,if(e.code=="key_not_pending") "That key was already decided." else "Couldn't deny. Try again.")
            } catch(e:SessionEndedException) {host.onSessionEnded(e);return@launch
            } catch(_:Exception) {snack(this@AiKeysScreen,"Couldn't confirm. Check the list.")}
            busy=false;load()
        }
    }
}

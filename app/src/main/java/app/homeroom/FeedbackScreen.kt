// RECONSTRUCTED Nov 23, 2026: not original source. Behavior recovered from
// the exact private12 release build (APK sha256
// f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d). The
// release organized this UI differently; file layout here is reconstructed
// as a standalone screen. Requires fresh review and tests before any
// release or equivalence claim.
package app.homeroom

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.UUID

/** Settings > Feedback & bugs: compose a report; the preview shows exactly what leaves the phone. */
class FeedbackScreen(context:Context):Screen(context) {
    private var message=""
    init {
        val body=column(context)
        body.setPadding(dp(16),dp(12),dp(16),dp(24))
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        val card=column(context).apply {background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
        body.addView(card,LinearLayout.LayoutParams(-1,-2))
        val edit=input(context,"What happened, or what would help?")
        edit.minLines=5
        edit.gravity=Gravity.TOP
        edit.background=rounded(C.surfaceAlt,dp(12).toFloat())
        edit.setPadding(dp(12),dp(10),dp(12),dp(10))
        // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.inputType=147457
        card.addView(edit,LinearLayout.LayoutParams(-1,-2))
        val preview=text(context,previewOf(""),12.5f,C.muted)
        edit.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
            override fun afterTextChanged(s:Editable) {message=s.toString();preview.text=previewOf(message)}
        })
        card.addView(preview,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        card.addView(text(context,"GitHub issues are public. Leave out anything private.",12.5f,C.accent),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        val row=LinearLayout(context)
        row.addView(button(context,"GitHub issue",false) {openGithubIssue()},LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(8)})
        row.addView(button(context,"Copy message",true) {copyMessage()},LinearLayout.LayoutParams(0,-2,1f))
        if(host.api.hasFeature("owner_room_management")) {
            val clientId=UUID.randomUUID().toString()
            card.addView(button(context,"Send to private Feedback room",true) {send(edit,clientId)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        }
        card.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
    }
    /** The exact block shown in the preview; the server receives it minus the "What will be sent:" header. */
    private fun previewOf(msg:String):String {
        val body=if(msg.isBlank()) "(your message)" else msg.trim().take(1000)
        return "What will be sent:\n"+body+"\nApp version 1.5 · Android "+android.os.Build.VERSION.RELEASE+"\nNothing else: no name, server or account."
    }
    private fun composed():String = previewOf(message).removePrefix("What will be sent:\n").trim()
    private fun openGithubIssue() {
        val url="https://github.com/MtndaleRedneck/HomeRoom/issues/new?title="+URLEncoder.encode("Feedback","UTF-8")+"&body="+URLEncoder.encode(composed().take(1500),"UTF-8")
        if(url.isBlank()) {snack(this,"Not set up yet.");return}
        try {host.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
        catch(_:ActivityNotFoundException) {snack(this,"No browser is available.")}
    }
    private fun copyMessage() {
        if(message.isBlank())return
        val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Feedback",message.trim()))
        snack(this,"Message copied.")
    }
    private fun send(edit:EditText,clientId:String) {
        screenScope.launch {
            try {
                host.api.sendFeedback(composed(),clientId)
                edit.setText("")
                snack(this@FeedbackScreen,"Saved in your private Feedback room.")
            } catch(e:SessionEndedException) {host.onSessionEnded(e.reason)
            } catch(_:Exception) {snack(this@FeedbackScreen,"Couldn't confirm. Check your Feedback room before trying again.")}
        }
    }
}

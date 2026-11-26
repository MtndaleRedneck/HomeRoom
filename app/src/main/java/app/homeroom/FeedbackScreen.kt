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
    companion object {
        // HomeRoom's own private feedback form (responses go to the owner's private sheet).
        const val FORM_URL="https://docs.google.com/forms/d/e/1FAIpQLSc6II7JLbc_kdHX3ELMnIB_YmfN2Trxh-zUbgqG60lzeUFuvg/viewform"
        const val MAX_MESSAGE=1000
        const val ENTRY_MESSAGE="entry.1658269790"
        const val ENTRY_MODEL="entry.490457509"
        const val ENTRY_ANDROID="entry.1177615215"
        const val ENTRY_APP_VERSION="entry.954364603"
        fun phoneModel():String {
            val maker=android.os.Build.MANUFACTURER.orEmpty().trim();val model=android.os.Build.MODEL.orEmpty().trim()
            return if(model.startsWith(maker,true)) model else "$maker $model".trim()
        }
        /** Hard cap on typed text; LengthFilter never splits a surrogate pair, so an emoji at the limit is dropped whole. */
        fun messageFilter():android.text.InputFilter=android.text.InputFilter.LengthFilter(MAX_MESSAGE)
        const val PREVIEW_HEADER="What will be sent:\n"
        /** The exact block shown in the preview. */
        fun previewBlock(msg:String,appVersion:String,android:String,model:String):String {
            val body=if(msg.isBlank()) "(your message)" else msg.trim()
            return PREVIEW_HEADER+body+"\nApp version "+appVersion+" · Android "+android+" · "+model+"\nNothing else: no name, server or account."
        }
        /** What GitHub body, Copy-adjacent room payload and preview carry: the preview minus its header. */
        fun outgoingText(msg:String,appVersion:String,android:String,model:String):String =
            previewBlock(msg,appVersion,android,model).removePrefix(PREVIEW_HEADER).trim()
        fun githubUrl(msg:String,appVersion:String,android:String,model:String):String =
            "https://github.com/MtndaleRedneck/HomeRoom/issues/new?title="+URLEncoder.encode("Feedback","UTF-8")+"&body="+URLEncoder.encode(outgoingText(msg,appVersion,android,model),"UTF-8")
        /** Prefilled viewform address. The reply-email entry is left empty on purpose. */
        fun formUrl(message:String,model:String,android:String,appVersion:String):String =
            FORM_URL+"?usp=pp_url&"+ENTRY_MESSAGE+"="+URLEncoder.encode(message,"UTF-8")+"&"+ENTRY_MODEL+"="+URLEncoder.encode(model,"UTF-8")+
                "&"+ENTRY_ANDROID+"="+URLEncoder.encode(android,"UTF-8")+"&"+ENTRY_APP_VERSION+"="+URLEncoder.encode(appVersion,"UTF-8")
    }
    private var editRef:EditText?=null
    /** The typed text, kept across a system recreation (font size, language) by MainActivity. */
    var draft:String
        get()=editRef?.text?.toString().orEmpty()
        set(v) {editRef?.setText(v)}
    init {
        val body=column(context)
        body.setPadding(dp(16),dp(12),dp(16),dp(110))
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,"Tell us what is on your mind: a bug, an idea, or general feedback. Reports may become public issues on GitHub, so leave out personal details.",13f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(14)})
        val card=sectionCard(context)
        body.addView(card,LinearLayout.LayoutParams(-1,-2))
        val edit=input(context,"What happened, or what would help?")
        editRef=edit
        edit.minLines=5
        edit.gravity=Gravity.TOP
        edit.background=rounded(C.surfaceAlt,dp(12).toFloat())
        edit.setPadding(dp(12),dp(10),dp(12),dp(10))
        // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.inputType=147457
        // Hard cap so the preview, GitHub body, form field, Copy and the private room all carry exactly the typed text (nothing is cut silently).
        edit.filters=arrayOf(messageFilter())
        card.addView(edit,LinearLayout.LayoutParams(-1,-2))
        val preview=text(context,previewOf(""),13f,C.muted)
        edit.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
            override fun afterTextChanged(s:Editable) {message=s.toString();preview.text=previewOf(message)}
        })
        val counter=text(context,"0 / "+MAX_MESSAGE+" characters",13f,C.muted)
        edit.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {};override fun afterTextChanged(s:Editable) {counter.text=s.length.toString()+" / "+MAX_MESSAGE+" characters"}})
        card.addView(counter,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        card.addView(preview,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        body.addView(actionButton(context,"Open GitHub issue",true) {openGithubIssue()},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        body.addView(text(context,"Public and easiest to follow up.",13f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
        body.addView(actionButton(context,"Open private form",true) {openForm()},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        body.addView(text(context,"No name needed. A reply email is optional.",13f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
        body.addView(actionButton(context,"Copy message",false) {copyMessage()},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        if(host.api.hasFeature("owner_room_management")) {
            body.addView(actionButton(context,"Send to private Feedback room",false) {send(edit)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        }
    }
    /** The exact block shown in the preview; the server receives it minus the "What will be sent:" header. */
    private fun previewOf(msg:String):String {
        return previewBlock(msg,BuildConfig.VERSION_NAME,android.os.Build.VERSION.RELEASE,phoneModel())
    }
    private fun composed():String = outgoingText(message,BuildConfig.VERSION_NAME,android.os.Build.VERSION.RELEASE,phoneModel())
    private fun guard():Boolean {
        if(message.isBlank()) {snack(this,"Write your message first.");return false}
        return true
    }
    private fun openUrl(url:String) {
        try {host.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
        catch(_:ActivityNotFoundException) {snack(this,"No browser is available.")}
    }
    private fun openGithubIssue() {
        if(!guard())return
        openUrl(githubUrl(message,BuildConfig.VERSION_NAME,android.os.Build.VERSION.RELEASE,phoneModel()))
    }
    private fun openForm() {
        if(!guard())return
        openUrl(formUrl(message.trim(),phoneModel(),android.os.Build.VERSION.RELEASE,BuildConfig.VERSION_NAME))
    }
    private fun copyMessage() {
        if(!guard())return
        val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Feedback",message.trim()))
        snack(this,"Message copied.")
    }
    /** One id per report: the same text after an unconfirmed send keeps its id (a retry replays); new text or a confirmed send gets a fresh one. Kept in memory across a recreation. */
    private fun clientIdFor(text:String):String {
        val r=host.retained
        val kept=r.feedbackClientId
        if(kept!=null && r.feedbackSentText==text)return kept
        return UUID.randomUUID().toString().also {r.feedbackClientId=it;r.feedbackSentText=text}
    }
    private fun send(edit:EditText) {
        if(!guard())return
        val text=composed()
        val clientId=clientIdFor(text)
        screenScope.launch {
            try {
                host.api.sendFeedback(text,clientId)
                host.retained.feedbackClientId=null;host.retained.feedbackSentText=null
                edit.setText("")
                snack(this@FeedbackScreen,"Saved in your private Feedback room.")
            } catch(e:SessionEndedException) {host.onSessionEnded(e)
            } catch(_:Exception) {snack(this@FeedbackScreen,"Couldn't confirm. Check your Feedback room before trying again.")}
        }
    }
}

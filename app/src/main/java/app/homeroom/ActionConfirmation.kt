package app.homeroom

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Must be switched only after this exact action UI, server and consumer pass release review. */
internal object ActionConfirmationGate {
    const val CLIENT_REVIEWED=false
    fun enabled(serverReviewed:Boolean)=CLIENT_REVIEWED && serverReviewed
}

/** Separate from the discussion dialog. Never calls an outside provider or claims an approval. */
internal object ActionConfirmation {
    internal fun reviewContent(host:MainActivity,card:DecisionCard):LinearLayout {
        val frozen=card.copy(action=card.action?.let {JSONObject(it.toString())})
        val details=frozen.actionReviewOrNull() ?: throw IllegalArgumentException("Unverified action")
        return column(host).apply {
            setPadding(host.dp(20),host.dp(12),host.dp(20),host.dp(12))
            addView(text(host,frozen.title,20f,C.text,true))
            addView(text(host,frozen.proposal,16f,C.text).apply {setTextIsSelectable(true)})
            addView(text(host,"Review the entire exact action. Confirm records permission for only this action and intended AI, for 15 minutes. HomeRoom does not execute it. The consumer must independently verify your authority, current details and any payment approval.",14f,C.amber))
            for((label,value) in details.fields) {
                addView(text(host,label,14f,C.muted,true))
                addView(text(host,value,16f,C.text).apply {setTextIsSelectable(true)})
            }
            addView(text(host,"A changed destination, content, attachment, payment method or total needs a new card. A claimed or uncertain action must not be retried.",14f,C.amber))
        }
    }
    fun show(host:MainActivity,card:DecisionCard,saved:JSONObject,serverReviewed:Boolean,completed:()->Unit) {
        require(ActionConfirmationGate.enabled(serverReviewed))
        val frozen=card.copy(action=card.action?.let {JSONObject(it.toString())})
        require(frozen.actionReviewOrNull()!=null && saved.getString("hash")==frozen.hash && saved.getString("option")=="yes")
        require(saved.getString("action_sha256")==frozen.actionHash)
        require(saved.getString("snapshot")==canonicalDecisionJson(frozen.immutableSnapshot()))
        val generation=host.session.generation
        val content=reviewContent(host,frozen)
        val scroll=object:ScrollView(host) {
            override fun onMeasure(width:Int,height:Int) {
                val cap=(host.resources.displayMetrics.heightPixels*0.60f).toInt().coerceAtLeast(host.dp(180))
                super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST))
            }
        }.apply {tag="action_review_scroll";addView(content)}
        val layout=column(host).apply {addView(scroll)}
        val bar=LinearLayout(host).apply {orientation=LinearLayout.HORIZONTAL;setPadding(host.dp(12),host.dp(8),host.dp(12),host.dp(12))}
        val dialog=AlertDialog.Builder(host,R.style.Theme_HomeRoom_Dialog).setTitle("Confirm this exact action?").setView(layout).create()
        val cancel=button(host,"Cancel",false) {dialog.dismiss()}.apply {tag="action_cancel"}
        val confirm=button(host,"Confirm",true) {}.apply {tag="action_confirm"}
        bar.addView(cancel,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=host.dp(8)})
        bar.addView(confirm,LinearLayout.LayoutParams(0,-2,1f));layout.addView(bar)
        confirm.setOnClickListener {
            val expired=try { !java.time.Instant.now().isBefore(java.time.Instant.parse(saved.getString("expires"))) }catch(_:Exception) {true}
            if(expired && !saved.optBoolean("attempted")) {snack(content,"Confirmation expired. Cancel and choose again.");return@setOnClickListener}
            confirm.isEnabled=false
            host.scope.launch {
                try {
                    host.session.withinGeneration(generation) {saved.put("attempted",true);host.session.saveDecisionReview(frozen.id,saved)}
                    val result=V15Api(host.api).confirm(frozen,saved.getString("confirmation"),saved.getString("action_id"))
                    val status=verifiedActionConfirmation(frozen,result)
                    host.session.withinGeneration(generation) {host.session.saveDecisionReview(frozen.id,null)}
                    dialog.dismiss()
                    snack(host.window.decorView,"Confirmation recorded. Server state: "+status.state+". This does not mean the action ran.")
                    completed()
                }catch(e:SessionEndedException) {dialog.dismiss();host.onSessionEnded(e)}
                catch(_:Exception) {snack(content,"Confirmation not verified. Check status before choosing again. Retry uses only the same confirmation ID.");confirm.isEnabled=true}
            }
        }
        dialog.showTracked()
    }
}
internal fun verifiedActionConfirmation(card:DecisionCard,response:JSONObject):ActionApproval {
    require(response.get("version") is Int || response.get("version") is Long)
    require(response.getString("decision_id")==card.id && response.getInt("version")==card.version)
    require(response.getString("state")=="answered" && response.getString("option_id")=="yes" && response.getString("option_label")=="Yes")
    require(response.getString("proposal_sha256")==card.hash)
    require(!response.has("confirmation_receipt") || response.isNull("confirmation_receipt"))
    return ActionApproval.parse(card,response)
}

/** Recomputed on every layout and scroll; reaching the end once is not a permanent bypass. */
internal class ActionReviewScrollGate(private val scroll:ScrollView,private val confirm:android.widget.Button) {
    private var busy=false
    init {
        confirm.isEnabled=false
        scroll.setOnScrollChangeListener {_:View, _:Int, _:Int, _:Int, _:Int->refresh()}
        scroll.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->refresh()}
        scroll.getChildAt(0)?.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->refresh()}
        scroll.post {refresh()}
    }
    fun canConfirm():Boolean {
        val child=scroll.getChildAt(0) ?: return false
        val viewport=scroll.height-scroll.paddingTop-scroll.paddingBottom
        return !busy && viewport>0 && child.height>0 && child.bottom<=scroll.scrollY+scroll.height-scroll.paddingBottom
    }
    fun refresh() {confirm.isEnabled=canConfirm()}
    fun setBusy(value:Boolean) {busy=value;refresh()}
}
internal fun decisionReviewResumable(saved:JSONObject,now:java.time.Instant=java.time.Instant.now()):Boolean {
    if(saved.optBoolean("attempted",false))return true // Exact-ID recovery only, never fresh permission.
    return try {now.isBefore(java.time.Instant.parse(saved.getString("expires")))}catch(_:Exception) {false}
}

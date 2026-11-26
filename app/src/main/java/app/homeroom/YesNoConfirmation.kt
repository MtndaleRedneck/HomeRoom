package app.homeroom

import android.app.AlertDialog
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun verifiedConfirmationReceipt(card:DecisionCard,result:JSONObject):JSONObject {
    require(card.discussionConfirmable)
    val receipt=result.getJSONObject("confirmation_receipt")
    require(receipt.getString("receipt_id").isNotBlank())
    require(receipt.getString("decision_id")==card.id && receipt.getInt("decision_version")==card.version)
    require(receipt.getString("option_id")=="yes" && receipt.getString("content_sha256")==card.hash)
    require(receipt.isNull("action_id") && receipt.isNull("action_sha256"))
    require(receipt.getString("source")=="authenticated_owner_session" && receipt.has("external_execution_enabled") && !receipt.getBoolean("external_execution_enabled"))
    java.time.Instant.parse(receipt.getString("confirmed_at"))
    require(!result.has("authorization") || result.isNull("authorization"))
    return receipt
}
internal object YesNoConfirmation {
    fun show(host:MainActivity,card:DecisionCard,saved:JSONObject,completed:()->Unit) {
        require(card.discussionConfirmable && saved.getString("hash")==card.hash && saved.getString("option")=="yes")
        val generation=host.session.generation
        val content=column(host).apply {setPadding(host.dp(20),host.dp(12),host.dp(20),host.dp(12))}
        content.addView(text(host,card.title,20f,C.text,true))
        content.addView(text(host,card.proposal,16f,C.text).apply {setTextIsSelectable(true)})
        content.addView(text(host,"Your answer: Yes\nAction: discussion answer only\nAmount: none\nRecipient: this HomeRoom room only",15f,C.text))
        content.addView(text(host,"Confirm records your Yes. It does not authorize or run an outside action.",14f,C.amber))
        val dialog=AlertDialog.Builder(host).setTitle("Confirm Yes?").setView(ScrollView(host).apply {addView(content)}).setNegativeButton("Cancel",null).setPositiveButton("Confirm",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!saved.optBoolean("attempted") && try {java.time.Instant.now().isAfter(java.time.Instant.parse(saved.getString("expires")))}catch(_:Exception) {true}) {snack(content,"Confirmation expired. Cancel and choose again.");return@setOnClickListener}
            val confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE);confirm.isEnabled=false
            host.scope.launch {
                try {
                    host.session.withinGeneration(generation) {saved.put("attempted",true);host.session.saveDecisionReview(card.id,saved)}
                    val receipt=verifiedConfirmationReceipt(card,V15Api(host.api).confirm(card,saved.getString("confirmation"),saved.getString("action_id")))
                    host.session.withinGeneration(generation) {host.session.saveDecisionReview(card.id,null)}
                    dialog.dismiss();snack(host.window.decorView,"Yes recorded. Receipt "+receipt.getString("receipt_id")+". No outside action authorized or run.");completed()
                }catch(e:SessionEndedException) {dialog.dismiss();host.onSessionEnded(e)}
                catch(_:Exception) {snack(content,"Confirmation not verified. Retry keeps the exact confirmation ID; do not choose again until checked.");confirm.isEnabled=true}
            }
        }}
        dialog.show()
    }
}
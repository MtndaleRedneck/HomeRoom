package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Explicit decisions never execute a callback or grant another service access. */
class DecisionScreen(context:Context,private val decisionId:String):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val service=V15Api(host.api)
    private var busy=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    private fun load() {screenScope.launch {
        try {render(service.decision(decisionId))}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load the decision.") {load()})}
    }}
    private fun render(card:DecisionCard) {
        body.removeAllViews()
        body.addView(text(context,card.title,22f,C.text,true))
        body.addView(text(context,card.proposal,16f,C.text).apply {setTextIsSelectable(true)})
        body.addView(text(context,"State: "+card.state+" · "+card.effect.replace('_',' '),13f,C.muted))
        body.addView(text(context,"A choice needs a separate confirmation. It does not send messages, spend money or approve keys/rules.",14f,C.amber))
        if(card.state!="open")return
        for(option in card.options) {
            val selectionId=clientId()
            body.addView(button(context,option.label,false) {if(!busy) {
                busy=true
                screenScope.launch {
                    try {
                        val prepared=service.prepare(card,option.id,selectionId)
                        require(prepared.getString("state")=="confirmation_required")
                        val confirmation=prepared.getJSONObject("confirmation")
                        require(confirmation.getString("content_sha256")==card.hash)
                        val proposal=confirmation.getJSONObject("proposal")
                        require(proposal.getString("title")==card.title && proposal.getString("proposal")==card.proposal && proposal.getString("effect_class")==card.effect)
                        val offered=proposal.requiredArray("options").objects().map {DecisionOption(it.getString("option_id"),it.getString("label"))}
                        require(offered==card.options)
                        val choice=confirmation.getJSONObject("option")
                        require(choice.getString("option_id")==option.id && choice.getString("label")==option.label)
                        host.openPage("Confirm your answer",DecisionConfirmScreen(context,card,option,prepared.getString("confirmation_id"),prepared.getString("expires_at")))
                    } catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
                    catch(_:Exception) {snack(this@DecisionScreen,"Selection not confirmed. No answer is recorded. Refresh before trying again.")}
                    finally {busy=false}
                }
            }})
        }
    }
}
internal class DecisionConfirmScreen(context:Context,private val card:DecisionCard,private val choice:DecisionOption,private val confirmation:String,private val expires:String):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val actionId=clientId()
    private var busy=false
    private var done=false
    init {
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,"Confirm this exact answer",22f,C.text,true))
        body.addView(text(context,card.title,18f,C.text,true))
        body.addView(text(context,card.proposal,16f,C.text).apply {setTextIsSelectable(true)})
        body.addView(text(context,"Your answer: "+choice.label,18f,C.accent,true))
        body.addView(text(context,"This records your answer in HomeRoom only. It does not execute an outside action. Confirm only if the proposal contains the full action, cost and audience you intend.",14f,C.amber))
        body.addView(button(context,"Confirm answer",true) {confirm()})
        body.addView(button(context,"Cancel",false) {host.onBackPressedDispatcher.onBackPressed()})
    }
    private fun confirm() {
        if(busy || done)return
        if(try {java.time.Instant.now().isAfter(java.time.Instant.parse(expires))}catch(_:Exception) {true}) {snack(this,"Confirmation expired. Go back and select again.");return}
        busy=true
        screenScope.launch {
            try {
                val receipt=V15Api(host.api).confirm(card,confirmation,actionId)
                require(receipt.getString("decision_id")==card.id && receipt.getString("option_id")==choice.id && receipt.getString("proposal_sha256")==card.hash && receipt.getString("state")=="answered")
                done=true;body.removeAllViews()
                body.addView(text(context,"Answer recorded",22f,C.green,true))
                body.addView(text(context,choice.label+"\n"+receipt.getString("confirmed_at"),16f,C.text))
                body.addView(text(context,"Server receipt: "+receipt.getString("answer_id")+". This is not proof that an outside action happened.",14f,C.muted))
            } catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {snack(this@DecisionConfirmScreen,"Answer not confirmed. Retry uses the same answer identity; do not choose again until checked.")}
            finally {busy=false}
        }
    }
}
class TeamScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    override fun poll() {load()}
    private var loading=false
    private fun load() {if(loading)return;loading=true;screenScope.launch {
        try {render(V15Api(host.api).team())}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load team state.") {load()})}
        finally {loading=false}
    }}
    internal fun render(data:JSONObject) {
        body.removeAllViews()
        body.addView(text(context,"Your AI team",22f,C.text,true))
        body.addView(text(context,"Work status is AI-reported. A check-in is not proof work finished or a message was read.",14f,C.muted))
        for(ai in data.requiredArray("actors").objects()) {
            val card=column(context).apply {background=rounded(C.surface,dp(16).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
            card.addView(text(context,ai.optString("display_name").ifBlank {ai.getString("actor")},18f,C.text,true))
            card.addView(text(context,"Last check-in: "+(ai.nullableString("last_authenticated_at") ?: "Unknown"),13f,C.muted))
            val state=ai.optJSONObject("work_state")
            card.addView(text(context,if(ai.optBoolean("stale") || state==null)"AI-reported: stale or unknown" else "AI-reported: "+state.optString("state")+"\n"+state.optString("summary"),15f,C.text))
            state?.nullableString("waiting_on")?.let {card.addView(text(context,"Waiting on: "+it,14f,C.amber))}
            val ack=ai.optJSONObject("guide_ack")
            card.addView(text(context,if(ack==null)"Guide: no acknowledgment recorded" else "Guide v"+ack.optInt("version")+" · "+(ack.nullableString("acknowledged_at") ?: "Time unknown")+if(ack.optBoolean("current"))" · current" else " · old",13f,C.muted))
            body.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
        }
    }
}
class RoomGoalScreen(context:Context,private val room:Room):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private var busy=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    private fun load() {screenScope.launch {
        try {render(V15Api(host.api).goal(room.id))}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load the room goal.") {load()})}
    }}
    private fun render(data:JSONObject) {
        body.removeAllViews();body.addView(text(context,"Pinned room goal",22f,C.text,true))
        val current=data.getJSONObject("current")
        body.addView(text(context,current.nullableString("text") ?: "No approved goal yet.",16f,C.text).apply {setTextIsSelectable(true)})
        body.addView(text(context,"AI proposals stay pending until you approve the exact text. A goal is context, not new permission.",14f,C.muted))
        body.addView(button(context,"Write a goal proposal",false) {
            val box=input(context,"Goal").apply {setText(current.nullableString("text").orEmpty())}
            val actionId=clientId()
            AlertDialog.Builder(context).setTitle("Propose room goal").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Propose") {_,_->
                val value=box.text.toString();if(value.isBlank() || value.length>4000) {snack(this,"Use 1 to 4000 characters.");return@setPositiveButton}
                change {V15Api(host.api).goalProposal(room.id,current.getInt("version"),value,actionId)}
            }.show()
        })
        for(proposal in data.requiredArray("pending_proposals").objects()) {
            body.addView(text(context,"Pending from "+proposal.getString("proposed_by"),14f,C.amber,true))
            body.addView(text(context,proposal.getString("text"),16f,C.text))
            for(decision in listOf("reject","approve")) {
                val actionId=clientId()
                body.addView(button(context,decision.replaceFirstChar {it.uppercase()},false) {
                    AlertDialog.Builder(context).setTitle(if(decision=="approve")"Make this goal live?" else "Reject this goal?")
                        .setMessage(proposal.getString("text")).setNegativeButton("Cancel",null).setPositiveButton("Confirm") {_,_->change {V15Api(host.api).goalDecision(proposal,decision,actionId)}}.show()
                })
            }
        }
    }
    private fun change(action:suspend ()->JSONObject) {if(busy)return;busy=true;screenScope.launch {
        try {action();snack(this@RoomGoalScreen,"Server recorded the change.");load()}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {snack(this@RoomGoalScreen,"Not confirmed. Refresh before trying again.")}
        finally {busy=false}
    }}
}
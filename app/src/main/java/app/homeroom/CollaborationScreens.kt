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
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load the decision.") {load()})}
    }}
    private fun render(card:DecisionCard) {
        body.removeAllViews()
        val c=sectionCard(context)
        c.addView(text(context,card.title,18f,C.text,true))
        c.addView(text(context,card.proposal,15f,C.text).apply {setTextIsSelectable(true)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        c.addView(text(context,"State: "+card.state+" · "+card.effect.replace('_',' '),12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        body.addCard(c)
        body.addCard(sectionCard(context).apply {addView(text(context,"A choice needs a separate confirmation. It does not send messages, spend money or approve keys/rules.",13.5f,C.text))})
        if(card.state!="open")return
        for(option in card.options) {
            val selectionId=clientId()
            body.addStacked(button(context,option.label,false) {if(!busy) {
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
                    } catch(e:SessionEndedException) {host.onSessionEnded(e)}
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
        val c=sectionCard(context,"Confirm this exact answer")
        c.addView(text(context,card.title,16f,C.text,true))
        c.addView(text(context,card.proposal,15f,C.text).apply {setTextIsSelectable(true)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        c.addView(text(context,"Your answer: "+choice.label,16f,C.accent,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        body.addCard(c)
        body.addCard(sectionCard(context).apply {addView(text(context,"This records your answer in HomeRoom only. It does not execute an outside action. Confirm only if the proposal contains the full action, cost and audience you intend.",13.5f,C.text))})
        body.addStacked(button(context,"Confirm answer",true) {confirm()})
        body.addStacked(button(context,"Cancel",false) {host.onBackPressedDispatcher.onBackPressed()})
    }
    // The server may have recorded the answer even when a call fails, so a failed or unreadable reply is never
    // treated as "not recorded": the same answer identity stays usable (the server replays an exact repeat) and the
    // screen says the outcome is unknown. A verified receipt is the only thing that shows "Answer recorded".
    private var attempted=false
    private fun confirm() {
        if(busy || done)return
        if(!attempted && (try {java.time.Instant.now().isAfter(java.time.Instant.parse(expires))}catch(_:Exception) {true})) {snack(this,"Confirmation expired. Go back and select again.");return}
        busy=true;attempted=true
        screenScope.launch {
            try {
                val receipt=V15Api(host.api).confirm(card,confirmation,actionId)
                when(val outcome=receiptOutcome(receipt,card.id,choice.id,card.hash)) {
                    is ReceiptOutcome.Recorded -> {
                        val recorded=recordedCard(context,choice.label,outcome) // fully built before the screen changes
                        done=true
                        body.removeAllViews();body.addCard(recorded)
                    }
                    ReceiptOutcome.Unverified -> snack(this@DecisionConfirmScreen,"The server's reply could not be verified for this answer. It may or may not be recorded. Check the decision before choosing again.")
                }
            } catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(_:Exception) {snack(this@DecisionConfirmScreen,"Couldn't confirm whether the answer was recorded. Tap Confirm answer again to check (same answer identity), or check the decision before choosing again.")}
            finally {busy=false}
        }
    }
}

internal sealed class ReceiptOutcome {
    data class Recorded(val answerId:String?,val confirmedAt:String?):ReceiptOutcome()
    object Unverified:ReceiptOutcome()
}

/** Verified recorded only when the receipt names this decision, this option and this proposal hash with state "answered". answer_id and confirmed_at are shown when present. */
internal fun receiptOutcome(receipt:JSONObject,decisionId:String,optionId:String,proposalSha:String):ReceiptOutcome {
    val bound=receipt.optString("decision_id")==decisionId && receipt.optString("option_id")==optionId &&
        receipt.optString("proposal_sha256")==proposalSha && receipt.optString("state")=="answered"
    if(!bound)return ReceiptOutcome.Unverified
    return ReceiptOutcome.Recorded(receipt.nullableString("answer_id"),receipt.nullableString("confirmed_at"))
}

internal fun recordedCard(context:Context,label:String,outcome:ReceiptOutcome.Recorded):LinearLayout {
    val card=sectionCard(context,"Answer recorded")
    card.addView(text(context,label+(outcome.confirmedAt?.let {"\n"+(formatTime(it).ifBlank {it})} ?: ""),15f,C.text))
    val receiptLine=outcome.answerId?.let {"Server receipt: "+it+"."} ?: "The server confirmed this answer but sent no receipt details."
    card.addView(text(context,receiptLine+" This is not proof that an outside action happened.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
    return card
}
class TeamScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    override fun poll() {load()}
    private var loading=false
    private fun load() {if(loading)return;loading=true;screenScope.launch {
        try {render(V15Api(host.api).team())}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load team state.") {load()})}
        finally {loading=false}
    }}
    internal fun render(data:JSONObject) {
        body.removeAllViews()
        body.addCard(sectionCard(context).apply {addView(text(context,"Work status is AI-reported. A check-in is not proof work finished or a message was read.",13f,C.muted))})
        if(data.requiredArray("actors").length()==0)body.addCard(emptyState(context,"No AIs are linked yet."))
        for(ai in data.requiredArray("actors").objects()) {
            val card=sectionCard(context)
            card.addView(text(context,ai.optString("display_name").ifBlank {ai.getString("actor")},17f,C.text,true))
            card.addView(text(context,"Last check-in: "+(ai.nullableString("last_authenticated_at")?.let {formatTime(it).ifBlank {it}} ?: "Unknown"),12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(2)})
            val state=ai.optJSONObject("work_state")
            card.addView(text(context,if(ai.optBoolean("stale") || state==null)"AI-reported: stale or unknown" else "AI-reported: "+state.optString("state")+"\n"+state.optString("summary"),14f,C.text),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            state?.nullableString("waiting_on")?.let {card.addView(text(context,"Waiting on: "+it,14f,C.amber))}
            val ack=ai.optJSONObject("guide_ack")
            card.addView(text(context,if(ack==null)"Guide: no acknowledgment recorded" else "Guide v"+ack.optInt("version")+" · "+(ack.nullableString("acknowledged_at")?.let {formatTime(it).ifBlank {it}} ?: "Time unknown")+if(ack.optBoolean("current"))" · current" else " · old",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
            body.addCard(card)
        }
    }
}
// private12 parity (K0/p3 mode 3 + K0/U0): the "AI answers" page after an
// ask-all. GET /owner/requests/<id> ONCE per page instance (release f1915o
// gate; re-showing does NOT refetch, retry happens via the error card - this
// is not a poll loop), one 300dp card per target in a horizontal strip:
// display name resolved from cached room members (fallback actor id), the
// question, the target state, and each reply event fetched and shown. All
// requests run against the captured operation auth.
class AskAllAnswersScreen(context:Context,private val requestId:String,private val launch:Session.OpAuth):Screen(context) {
    private val strip=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(12),dp(12),dp(24))}
    private var loaded=false
    // Reload generation: a retry can overlap in-flight reply fetches from the
    // failed render; stale completions must not write into the new strip.
    private var generation=0
    init {addView(android.widget.HorizontalScrollView(context).apply {addView(strip)},LayoutParams(-1,-1))}
    override fun onShow() {if(!loaded) {loaded=true;load()}}
    private fun load() {screenScope.launch {
        try {render(V15Api(host.api,launch).askRequest(requestId))}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {strip.removeAllViews();strip.addView(errorCard(context,"Couldn't load the AI answers.") {load()})}
    }}
    private fun render(data:JSONObject) {
        strip.removeAllViews()
        val gen=++generation
        val question=data.optString("text")
        for(target in data.requiredArray("targets").objects()) {
            val actor=target.getString("actor")
            val card=column(context).apply {
                setPadding(dp(12),dp(12),dp(12),dp(12))
                setBackgroundColor(C.surface)
            }
            // private12 parity (K0/U0): each card scrolls vertically, so a long
            // reply never overflows the strip. fillViewport keeps the surface
            // filling short cards too (GPT/Gemini layout must-fix).
            val cardScroll=ScrollView(context).apply {
                isFillViewport=true
                setBackgroundColor(C.surface)
                addView(card,LayoutParams(-1,-2))
            }
            val lp=LinearLayout.LayoutParams(dp(300),-1).apply {rightMargin=dp(12)}
            val displayName=host.cachedRooms().flatMap {it.members}.firstOrNull {it.first==actor}?.second?.takeIf {it.isNotBlank()} ?: actor
            card.addView(text(context,displayName,18f,C.text,true))
            card.addView(text(context,question,15f,C.text))
            val reason=target.nullableString("reason")
            card.addView(text(context,"State: "+target.getString("state")+(reason?.let {"\n"+it} ?: "")+"\nNotifications are not proof of reading.",13f,C.muted))
            val replies=target.optJSONArray("replies")
            if(replies!=null) {
                for(i in 0 until replies.length()) {
                    val eventId=replies.getJSONObject(i).optString("event_id")
                    if(eventId.isBlank()) continue
                    val replyView=text(context,"…",15f,C.text)
                    replyView.setTextIsSelectable(true)
                    card.addView(replyView)
                    screenScope.launch {
                        try {
                            if(gen!=generation) return@launch
                            val event=host.api.event(eventId,launch).getJSONObject("event")
                            if(gen!=generation) return@launch
                            // private12 guard (K0/U0): only render a reply whose
                            // actor matches the target and whose request ref is
                            // this ask; a mismatched reply is removed, not left
                            // as an indefinite loading placeholder.
                            if(event.optString("actor")!=actor) {card.removeView(replyView);return@launch}
                            if(event.nullableString("request_id")!=requestId) {card.removeView(replyView);return@launch}
                            replyView.text=event.getString("text")
                        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
                        catch(e:SessionEndedException) {host.onSessionEnded(e)}
                        catch(_:Exception) {if(gen==generation) replyView.text="Couldn't load this reply."}
                    }
                }
            }
            strip.addView(cardScroll,lp)
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
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load the room goal.") {load()})}
    }}
    private fun render(data:JSONObject) {
        body.removeAllViews()
        val current=data.getJSONObject("current")
        val gc=sectionCard(context,"Current goal")
        val goalText=current.nullableString("text")
        if(goalText==null)gc.addView(text(context,"No approved goal yet.",13f,C.muted)) else gc.addView(text(context,goalText,15f,C.text).apply {setTextIsSelectable(true)})
        gc.addView(text(context,"AI proposals stay pending until you approve the exact text. A goal is context, not new permission.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        body.addCard(gc)
        body.addStacked(button(context,"Write a goal proposal",false) {
            val box=outlinedInput(context,"Goal").apply {setText(current.nullableString("text").orEmpty())}
            val actionId=clientId()
            AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle("Propose room goal").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Propose") {_,_->
                val value=box.text.toString();if(value.isBlank() || value.length>4000) {snack(this,"Use 1 to 4000 characters.");return@setPositiveButton}
                change {V15Api(host.api).goalProposal(room.id,current.getInt("version"),value,actionId)}
            }.show()
        })
        for(proposal in data.requiredArray("pending_proposals").objects()) {
            val pc=sectionCard(context)
            body.addCard(pc)
            pc.addView(text(context,"Pending from "+proposal.getString("proposed_by"),13f,C.amber,true))
            pc.addView(text(context,proposal.getString("text"),15f,C.text),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4)})
            for(decision in listOf("reject","approve")) {
                val actionId=clientId()
                pc.addStacked(button(context,decision.replaceFirstChar {it.uppercase()},decision=="approve") {
                    AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle(if(decision=="approve")"Make this goal live?" else "Reject this goal?")
                        .setMessage(proposal.getString("text")).setNegativeButton("Cancel",null).setPositiveButton("Confirm") {_,_->change {V15Api(host.api).goalDecision(proposal,decision,actionId)}}.show()
                })
            }
        }
    }
    private fun change(action:suspend ()->JSONObject) {if(busy)return;busy=true;screenScope.launch {
        try {action();snack(this@RoomGoalScreen,"Server recorded the change.");load()}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {snack(this@RoomGoalScreen,"Not confirmed. Refresh before trying again.")}
        finally {busy=false}
    }}
}
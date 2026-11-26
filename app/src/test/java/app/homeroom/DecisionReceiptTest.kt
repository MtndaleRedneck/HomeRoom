package app.homeroom

import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The recorded-answer card is built from a verified receipt; incomplete or mismatched replies are never shown as recorded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DecisionReceiptTest {
    private val captures=JSONArray(javaClass.getResourceAsStream("/hr15-yesno-captures.json")!!.bufferedReader().readText())
    private fun capture(name:String):JSONObject {
        for(i in 0 until captures.length()) {val c=captures.getJSONObject(i);if(c.getString("name")==name)return c.getJSONObject("body")}
        error("capture $name missing")
    }
    private fun texts(v:android.view.View,out:MutableList<String> = mutableListOf()):List<String> {
        if(v is TextView)out.add(v.text.toString())
        if(v is android.view.ViewGroup)for(i in 0 until v.childCount)texts(v.getChildAt(i),out)
        return out
    }
    private val receipt get()=capture("confirmed_receipt")
    private fun outcome(r:JSONObject)=receiptOutcome(r,r.getString("decision_id"),r.getString("option_id"),r.getString("proposal_sha256"))

    @Test fun capturedServerReceiptIsVerifiedAndRendersTheRecordedOutcome() {
        val o=outcome(receipt)
        assertTrue(o is ReceiptOutcome.Recorded)
        val card=recordedCard(RuntimeEnvironment.getApplication(),"Yes",o as ReceiptOutcome.Recorded)
        val all=texts(card).joinToString("\n")
        assertTrue(all.contains("Answer recorded"))
        assertTrue(all.contains("Yes"))
        assertTrue(all.contains("Server receipt: "+receipt.getString("answer_id")))
        assertTrue(all.contains("not proof that an outside action happened"))
    }

    @Test fun recordedReceiptMissingAnswerIdAndTimeStillShowsRecordedWithATruthfulNote() {
        val r=JSONObject(receipt.toString());r.remove("answer_id");r.remove("confirmed_at")
        val o=outcome(r)
        assertTrue(o is ReceiptOutcome.Recorded)
        val all=texts(recordedCard(RuntimeEnvironment.getApplication(),"Yes",o as ReceiptOutcome.Recorded)).joinToString("\n")
        assertTrue(all.contains("Answer recorded"))
        assertTrue(all.contains("Yes"))
        assertTrue(all.contains("sent no receipt details"))
    }

    @Test fun wrongOptionHashDecisionOrStateIsNotShownAsRecorded() {
        for(field in listOf("decision_id","option_id","proposal_sha256")) {
            val r=JSONObject(receipt.toString());r.put(field,"other")
            assertEquals(field,ReceiptOutcome.Unverified,receiptOutcome(r,receipt.getString("decision_id"),receipt.getString("option_id"),receipt.getString("proposal_sha256")))
        }
        val notAnswered=JSONObject(receipt.toString()).put("state","pending")
        assertEquals(ReceiptOutcome.Unverified,outcome(notAnswered))
        val noState=JSONObject(receipt.toString());noState.remove("state")
        assertEquals(ReceiptOutcome.Unverified,outcome(noState))
    }

    @Test fun exactReplayReceiptAfterExpiryIsAlsoVerified() {
        assertTrue(outcome(capture("exact_confirm_replay_after_prepared_expiry")) is ReceiptOutcome.Recorded)
    }

    @Test fun capturedDefiniteFailuresAreClassifiedAndEverythingElseStaysUnknown() {
        assertEquals(ConfirmFailure.Expired,confirmFailure(410,"confirmation_expired"))
        assertEquals(ConfirmFailure.AlreadyAnswered,confirmFailure(409,"decision_already_answered"))
        assertEquals(ConfirmFailure.Unknown,confirmFailure(500,"server_error"))
        assertEquals(ConfirmFailure.Unknown,confirmFailure(408,"timeout"))
        assertEquals(ConfirmFailure.Unknown,confirmFailure(409,"decision_version_conflict"))
        assertEquals(ConfirmFailure.Unknown,confirmFailure(410,"approval_expired"))
    }

    @Test fun failureCardsAreTruthfulAboutWhatIsKnown() {
        val ctx=RuntimeEnvironment.getApplication()
        val exp=texts(failureCard(ctx,ConfirmFailure.Expired)).joinToString("\n")
        assertTrue(exp.contains("Confirmation expired")&&exp.contains("before the answer was recorded"))
        val ans=texts(failureCard(ctx,ConfirmFailure.AlreadyAnswered)).joinToString("\n")
        assertTrue(ans.contains("Already answered")&&ans.contains("may be a different answer"))
    }
}

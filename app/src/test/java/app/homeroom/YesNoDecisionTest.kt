package app.homeroom
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class YesNoDecisionTest {
    private val captures=JSONArray(javaClass.getResourceAsStream("/hr15-yesno-captures.json")!!.bufferedReader().readText()).objects()
    private fun body(name:String)=captures.first {it.getString("name")==name}.getJSONObject("body")
    @Test fun exactDiscussionSnapshotCanonicalHash() {
        val card=DecisionCard.from(body("created"));assertTrue(card.discussionConfirmable)
        assertEquals("92398691d0ee9b03c6dbb6b08b345229a8a58c3b1809f38805ff55c755724482",sha256(canonicalDecisionJson(card.immutableSnapshot()).toByteArray(Charsets.UTF_8)))
        assertEquals(canonicalDecisionJson(card.immutableSnapshot()),canonicalDecisionJson(body("yes_prepared_no_answer").getJSONObject("confirmation").getJSONObject("proposal")))
    }
    @Test fun plainYesContainsNoAnswerOrReceipt() {
        val prepared=body("yes_prepared_no_answer")
        assertEquals("confirmation_required",prepared.getString("state"));assertFalse(prepared.has("answer_id"));assertFalse(prepared.has("confirmation_receipt"))
    }
    @Test fun confirmedReceiptReplaysWithoutNewAuthority() {
        val card=DecisionCard.from(body("created"))
        val first=verifiedConfirmationReceipt(card,body("confirmed_receipt"));val replay=verifiedConfirmationReceipt(card,body("exact_confirm_replay_after_prepared_expiry"))
        assertEquals(first.getString("receipt_id"),replay.getString("receipt_id"));assertEquals(first.getString("confirmed_at"),replay.getString("confirmed_at"));assertFalse(first.getBoolean("external_execution_enabled"))
    }
    @Test fun noHasNoReceiptAndOldOptionsAreReadOnly() {
        val no=body("no_rejected");assertEquals("rejected",no.getString("state"));assertEquals("no",no.getString("option_id"));assertTrue(no.isNull("confirmation_receipt"))
        val card=DecisionCard.from(body("created"));assertFalse(card.copy(decisionType=null).discussionConfirmable)
        assertFalse(card.copy(action=JSONObject().put("kind","email")).discussionConfirmable)
        assertFalse(card.copy(proposal="Changed").discussionConfirmable)
    }
    @Test fun receiptCannotUpgradeExecutionOrChangeCard() {
        val card=DecisionCard.from(body("created"))
        for(change in listOf("external_execution_enabled","decision_id","content_sha256","action_id")) {
            val result=JSONObject(body("confirmed_receipt").toString());val receipt=result.getJSONObject("confirmation_receipt")
            receipt.put(change,if(change=="external_execution_enabled")true else "different")
            try {verifiedConfirmationReceipt(card,result);fail("accepted "+change)}catch(_:IllegalArgumentException) {}
        }
    }
}
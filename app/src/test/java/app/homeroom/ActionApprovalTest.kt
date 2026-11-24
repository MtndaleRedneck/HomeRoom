package app.homeroom

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ActionApprovalTest {
    private val captures=JSONArray(javaClass.getResourceAsStream("/hr15-action-captures35.json")!!.bufferedReader().readText()).objects()
    private fun body(name:String)=JSONObject(captures.first {it.getString("name")==name}.getJSONObject("body").toString())
    private fun card()=DecisionCard.from(body("signed_create"))
    private fun rejects(response:JSONObject) {try {ActionApproval.parse(card(),response);fail("Invalid receipt accepted")}catch(_:IllegalArgumentException) {}catch(_:IllegalStateException) {}}
    @Test fun authenticatedCapturesBindEntireCardAndActor() {
        val card=card();assertNotNull(card.actionReviewOrNull())
        for(name in listOf("owner_confirm","owner_get_decision_receipt_beside_current_approval_readiness","owner_get_approval","exact_confirm_replay","signed_claim","exact_claim_replay_current_state","owner_get_claimed","signed_result_unknown")) {
            val report=ActionApproval.parse(card,body(name))
            assertEquals("needs_owner_authority",report.readiness)
            assertEquals("receipt_a6e06503851524be18f0e37e",report.receiptId)
        }
        val report=ActionApproval.parse(card,body("signed_result_unknown"))
        assertEquals("outcome_unknown",report.state)
        assertTrue(report.fields.any {it.second.contains("Do not retry")})
    }
    @Test fun exactReplayRetainsOriginalDeadlineAndReceipt() {
        val first=ActionApproval.parse(card(),body("owner_confirm"));val replay=ActionApproval.parse(card(),body("exact_confirm_replay"))
        assertEquals(first.receiptId,replay.receiptId);assertEquals(first.expiresAt,replay.expiresAt)
    }
    @Test fun changedActorScopeSnapshotOrExecutionFlagRejected() {
        rejects(body("owner_confirm").apply {getJSONObject("receipt").getJSONObject("authorized_actor").put("key_id","different")})
        rejects(body("owner_confirm").apply {getJSONObject("receipt").put("action_sha256","0".repeat(64))})
        rejects(body("owner_confirm").apply {getJSONObject("snapshot").put("proposal","Changed")})
        rejects(body("owner_confirm").apply {getJSONObject("receipt").put("execution_by_homeroom",true)})
        rejects(body("owner_confirm").apply {getJSONObject("approval").put("expires_at","2026-11-12T17:26:53.596Z")})
        rejects(body("owner_confirm").apply {getJSONObject("receipt").put("decision_version",true)})
    }
    @Test fun consumedAndUnknownAreNotAvailableApprovals() {
        val claimed=ActionApproval.parse(card(),body("signed_claim"));assertEquals("claimed",claimed.state)
        val unknown=body("signed_result_unknown");unknown.getJSONObject("approval").put("last_result",JSONObject.NULL)
        assertEquals("outcome_unknown",ActionApproval.parse(card(),unknown).state) // Timeout does not invent provider evidence.
        rejects(body("signed_claim").apply {getJSONObject("approval").put("claim_id",JSONObject.NULL)})
        rejects(body("signed_result_unknown").apply {getJSONObject("approval").put("state","authorized")})
    }
    @Test fun successNeedsExplicitConsumerEvidenceNotAnHttpSubmit() {
        val response=body("signed_result_unknown");val approval=response.getJSONObject("approval");approval.put("state","succeeded");approval.getJSONObject("last_result").put("outcome","succeeded")
        rejects(response)
        approval.getJSONObject("last_result").put("provider_reference","fixture_provider_reference")
        val status=ActionApproval.parse(card(),response)
        assertTrue(status.fields.any {it.first.contains("not independently verified")})
    }
    @Test fun fixtureWithArtificiallyMutatedExpiryIsNotAValidProductionReceipt() {
        val raw=body("owner_get_expired_current_state_unchanged_receipt")
        val receipt=raw.getJSONObject("receipt")
        val c=card().copy(id=receipt.getString("decision_id"))
        try {ActionApproval.parse(c,raw);fail("Artificially changed current expiry accepted")}catch(_:IllegalArgumentException) {}
        raw.getJSONObject("approval").put("expires_at",receipt.getString("expires_at"))
        assertEquals("expired",ActionApproval.parse(c,raw).state)
    }
}

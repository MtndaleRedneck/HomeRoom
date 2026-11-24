package app.homeroom

import org.json.JSONObject
import java.time.Instant

/** A server status report, not consumer authority or verified provider evidence. */
internal data class ActionApproval(val state:String,val receiptId:String,val expiresAt:String,val readiness:String,val fields:List<Pair<String,String>>) {
    companion object {
        fun parse(card:DecisionCard,response:JSONObject):ActionApproval {
            require(card.actionReviewOrNull()!=null)
            val receipt=response.getJSONObject("receipt")
            receipt.exactApproval("receipt_id","approval_id","decision_id","decision_version","option_id","content_sha256","action_sha256","authorized_actor","confirmed_at","expires_at","source","execution_by_homeroom")
            require(receipt.get("decision_version") is Int || receipt.get("decision_version") is Long)
            require(receipt.getInt("decision_version")==card.version && receipt.getString("decision_id")==card.id && receipt.getString("option_id")=="yes")
            require(receipt.getString("content_sha256")==card.hash && receipt.getString("action_sha256")==card.actionHash)
            require(canonicalDecisionJson(receipt.getJSONObject("authorized_actor"))==canonicalDecisionJson(card.action!!.getJSONObject("authorized_actor")))
            require(receipt.getString("source")=="authenticated_owner_session" && receipt.get("execution_by_homeroom")==false)
            require(canonicalDecisionJson(response.getJSONObject("snapshot"))==canonicalDecisionJson(card.immutableSnapshot()))
            val confirmed=Instant.parse(receipt.getString("confirmed_at"));val expires=Instant.parse(receipt.getString("expires_at"))
            require(expires==confirmed.plusSeconds(900))
            val approval=response.getJSONObject("approval")
            approval.exactApproval("approval_id","state","claim_id","consumer_operation_id","claimed_at","last_result","expires_at","execution_by_homeroom")
            require(approval.getString("approval_id")==receipt.getString("approval_id") && approval.get("execution_by_homeroom")==false)
            require(approval.getString("expires_at")==receipt.getString("expires_at")) // Immutable deadline, never silently renewed.
            val state=approval.getString("state")
            require(state in setOf("authorized","claimed","succeeded","failed_no_effect","outcome_unknown","expired"))
            val consumed=state in setOf("claimed","succeeded","failed_no_effect","outcome_unknown")
            for(k in listOf("claim_id","consumer_operation_id","claimed_at")) {
                if(consumed)require(approval.get(k) is String && approval.getString(k).isNotBlank()) else require(approval.isNull(k))
            }
            if(consumed)Instant.parse(approval.getString("claimed_at"))
            val fields=mutableListOf<Pair<String,String>>()
            fields.add("Server approval state" to state)
            fields.add("Original confirmation time" to receipt.getString("confirmed_at"))
            fields.add("Original approval deadline" to receipt.getString("expires_at"))
            fields.add("Immutable receipt" to receipt.getString("receipt_id"))
            if(!approval.isNull("last_result")) {
                require(state in setOf("succeeded","failed_no_effect","outcome_unknown"))
                val result=approval.getJSONObject("last_result")
                result.exactApproval("result_seq","outcome","provider_reference","verified_state","checked_at")
                require(result.get("result_seq") is Int || result.get("result_seq") is Long)
                require(result.getLong("result_seq")>0 && result.getString("outcome")==state)
                Instant.parse(result.getString("checked_at"))
                val verified=result.get("verified_state") as? String ?: error("Verified state required")
                require(verified.isNotBlank() && verified.length<=1000 && verified.none {it.code<32 || it.code in 127..159})
                if(state=="succeeded")require(!result.isNull("provider_reference") && result.getString("provider_reference").isNotBlank())
                fields.add("Consumer-reported provider state (not independently verified by HomeRoom)" to verified)
                fields.add("Consumer evidence checked at" to result.getString("checked_at"))
                if(!result.isNull("provider_reference"))fields.add("Consumer-reported provider reference" to result.getString("provider_reference"))
            } else require(state !in setOf("succeeded","failed_no_effect"))
            val readiness=response.getJSONObject("consumer_readiness")
            readiness.exactApproval("state","reason_code","checked_at")
            val ready=readiness.getString("state");require(ready in setOf("needs_owner_authority","ready"))
            val reason=readiness.getString("reason_code");require(reason.length in 1..240 && reason.none {it.code<32 || it.code in 127..159})
            if(!readiness.isNull("checked_at"))Instant.parse(readiness.getString("checked_at"))
            fields.add("Consumer-reported readiness" to ready)
            fields.add("Readiness reason" to reason)
            fields.add("What this status means" to when(state) {
                "authorized" -> "The server recorded confirmation. This is not proof the consumer has authority or that anything ran."
                "claimed" -> "One consumer has reserved this approval. It is consumed and cannot be claimed or submitted again."
                "succeeded" -> "The consumer reports success. HomeRoom has not independently verified the provider result."
                "failed_no_effect" -> "The consumer reports no effect. This approval stays consumed. Another attempt needs a new card and confirmation."
                "outcome_unknown" -> "The outcome is unknown. Do not retry. Reconcile the original provider operation first. This approval stays consumed."
                else -> "This approval expired. It cannot be used or renewed by replaying confirmation."
            })
            return ActionApproval(state,receipt.getString("receipt_id"),receipt.getString("expires_at"),ready,fields)
        }
    }
}
private fun JSONObject.exactApproval(vararg fields:String) {require(keys().asSequence().toSet()==fields.toSet())}

// RECONSTRUCTED Nov 23, 2026: not original source. Canonical decision JSON and
// its string escaping recovered exactly from the exact private12 release build
// (sorted keys, JSON escaping with \uXXXX for control chars, numberToString for
// numbers, "Unsupported decision snapshot value" for anything else).
internal fun canonicalDecisionJson(value: Any?): String = when {
    value == null || value == JSONObject.NULL -> "null"
    value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        canonicalDecisionString(it) + ":" + canonicalDecisionJson(value.get(it))
    }
    value is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
        canonicalDecisionJson(value.get(it))
    }
    value is String -> canonicalDecisionString(value)
    value is Boolean -> value.toString()
    value is Number -> JSONObject.numberToString(value)
    else -> throw IllegalArgumentException("Unsupported decision snapshot value")
}

private fun canonicalDecisionString(value: String): String {
    val out = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\r' -> out.append("\\r")
            else -> if (c < ' ') out.append("\\u" + c.code.toString(16).padStart(4, '0')) else out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

// Actual original source (ActionApproval.kt read Nov 12 11:44:13 EST). Unknown
// states THROW per the owning function; callers must only pass validated states
// (ActionApproval.parse already restricts the state set before this can apply).
internal fun actionApprovalStateLabel(state:String):String=when(state) {
    "authorized"->"Confirmation recorded; not yet reserved"
    "claimed"->"Reserved by the original consumer; not proof of execution"
    "succeeded"->"Consumer reports completed"
    "failed_no_effect"->"Consumer reports no action occurred"
    "outcome_unknown"->"Outcome unknown; do not retry"
    "expired"->"Expired; no new action may start"
    else->throw IllegalArgumentException("Unknown action state")
}

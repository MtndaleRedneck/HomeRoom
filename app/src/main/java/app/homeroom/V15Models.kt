package app.homeroom

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

internal fun clientId()=UUID.randomUUID().toString()
internal data class DecisionOption(val id:String,val label:String)
// RECONSTRUCTED Nov 23, 2026: field set and parser recovered exactly from the
// exact private12 release build (decision identity, version bounds, conditional
// nullable fields, approval wrapper keys, normalized content hash).
internal data class DecisionCard(val id:String,val room:String,val version:Int,val hash:String,val title:String,val proposal:String,val options:List<DecisionOption>,val state:String,val effect:String,val actor:String,val contextEventIds:List<String>,val decisionType:String?,val action:JSONObject?,val actionHash:String?,val approval:JSONObject?) {
    companion object {
        fun from(o:JSONObject):DecisionCard {
            val v=o.get("version");require(v is Int || v is Long);val version=(v as Number).toLong();require(version in 1 until 0x80000000L)
            for(k in listOf("decision_id","room_id","content_sha256","title","proposal","state","effect_class"))require(o.get(k) is String)
            for(k in listOf("decision_type","action_sha256"))require(!o.has(k) || o.isNull(k) || o.get(k) is String)
            require(!o.has("action") || o.isNull("action") || o.get("action") is JSONObject)
            val hash=UpdateCheck.parseDigest(o.getString("content_sha256"))?.lowercase() ?: throw ApiException(0,"invalid_decision_hash")
            val options=o.requiredArray("options").objects().map {DecisionOption(it.getString("option_id"),it.getString("label"))}
            require(options.size in 2..6 && options.map {it.id}.distinct().size==options.size)
            val context=o.optJSONArray("context_event_ids")?.let {a -> (0 until a.length()).map {a.getString(it)}} ?: emptyList()
            val approval=if(o.has("approval") && !o.isNull("approval")) {
                JSONObject().put("approval",o.get("approval"))
                    .put("receipt",o.opt("receipt") ?: JSONObject.NULL)
                    .put("snapshot",o.opt("snapshot") ?: JSONObject.NULL)
                    .put("consumer_readiness",o.opt("consumer_readiness") ?: JSONObject.NULL)
                    .apply {if(o.has("consumer_access"))put("consumer_access",o.get("consumer_access"))}
            } else null
            return DecisionCard(o.getString("decision_id"),o.getString("room_id"),version.toInt(),hash,o.getString("title"),o.getString("proposal"),options,o.getString("state"),o.getString("effect_class"),o.optString("actor"),context,o.nullableString("decision_type"),o.optJSONObject("action"),o.nullableString("action_sha256"),approval)
        }
    }
}
// RECONSTRUCTED Nov 23, 2026: signed-in device row, fields recovered from the
// exact private12 release build (id + label required; created/last_used nullable).
internal data class OwnerDevice(val id:String,val label:String,val created:String?,val lastUsed:String?) {
    fun isCurrent(sessionId:String?)=sessionId!=null && id==sessionId
    companion object {fun from(o:JSONObject)=OwnerDevice(o.getString("id"),o.getString("label"),o.nullableString("created"),o.nullableString("last_used"))}
}
internal data class UploadStatus(val id:String,val state:String,val offset:Long,val size:Long,val expected:String,val verified:String?) {
    companion object {fun from(o:JSONObject)=UploadStatus(o.getString("upload_id"),o.getString("state"),o.getLong("next_offset"),o.getLong("byte_size"),o.getString("sha256"),o.nullableString("verified_sha256"))}
}
// initiating: when supplied (composer flows), every call is bound to that
// operation identity via ApiClient.requestFor - a session move rejects with
// stale_operation instead of carrying old work to a new account/server.
internal class V15Api(private val api:ApiClient,private val initiating:Session.OpAuth?=null) {
    private fun id(value:String)=ApiClient.enc(value)
    suspend fun get(path:String)=if(initiating!=null) api.requestFor(initiating,"/owner/"+path) else api.request("/owner/"+path)
    suspend fun mutate(path:String,body:JSONObject,method:String="POST")=if(initiating!=null) api.requestFor(initiating,"/owner/"+path,method,body) else api.request("/owner/"+path,method,body)
    suspend fun decision(key:String)=DecisionCard.from(get("decisions/"+id(key)))
    suspend fun prepare(card:DecisionCard,option:String,client:String)=mutate("decisions/"+id(card.id)+"/prepare-answer",JSONObject().put("client_id",client).put("version",card.version).put("content_sha256",card.hash).put("option_id",option))
    // RECONSTRUCTED: the release confirm-answer body always carries action_sha256
    // (JSON null for discussion cards) and reject-answer answers No without a dialog.
    suspend fun confirm(card:DecisionCard,confirmation:String,client:String)=mutate("decisions/"+id(card.id)+"/confirm-answer",JSONObject().put("client_id",client).put("confirmation_id",confirmation).put("content_sha256",card.hash).put("action_sha256",card.actionHash ?: JSONObject.NULL).put("confirmed",true))
    suspend fun reject(card:DecisionCard,client:String)=mutate("decisions/"+id(card.id)+"/reject-answer",JSONObject().put("client_id",client).put("version",card.version).put("content_sha256",card.hash))
    suspend fun team()=get("team")
    suspend fun goal(room:String)=get("rooms/"+id(room)+"/goal")
    suspend fun goalProposal(room:String,base:Int,text:String,client:String)=mutate("rooms/"+id(room)+"/goal-proposals",JSONObject().put("client_id",client).put("base_version",base).put("text",text))
    suspend fun goalDecision(proposal:JSONObject,decision:String,client:String)=mutate("goal-proposals/"+id(proposal.getString("proposal_id"))+"/decision",JSONObject().put("client_id",client).put("base_version",proposal.getInt("base_version")).put("proposal_sha256",proposal.getString("proposal_sha256")).put("decision",decision))
    suspend fun reaction(event:String,reaction:String?,client:String)=mutate("events/"+id(event)+"/reaction",JSONObject().put("client_id",client).put("reaction",reaction ?: JSONObject.NULL),"PUT")
    suspend fun edit(event:ChatEvent,text:String,client:String)=mutate("events/"+id(event.eventId)+"/edit",JSONObject().put("client_id",client).put("version",event.version).put("text",text))
    suspend fun delete(event:ChatEvent,client:String)=mutate("events/"+id(event.eventId)+"/delete",JSONObject().put("client_id",client).put("version",event.version))
    suspend fun ask(room:String,text:String,actors:List<String>,client:String,created:String)=mutate("rooms/"+id(room)+"/ask-all",JSONObject().put("client_id",client).put("client_created_at",created).put("text",text).put("actors",JSONArray(actors)).put("upload_ids",JSONArray()))
    suspend fun request(key:String)=get("requests/"+id(key))
    suspend fun status()=get("status")
    suspend fun testAlert(client:String)=mutate("status/test-alert",JSONObject().put("client_id",client))
    suspend fun testStatus(key:String)=get("status/test-alerts/"+id(key))
    suspend fun testReceipt(key:String,stage:String,client:String)=mutate("status/test-alerts/"+id(key)+"/receipts",JSONObject().put("client_id",client).put("stage",stage))
    suspend fun export(room:String,client:String)=mutate("rooms/"+id(room)+"/exports",JSONObject().put("client_id",client).put("format","bundle").put("include_attachments",true))
    suspend fun exportStatus(key:String)=get("exports/"+id(key))
    suspend fun cancelExport(key:String)=api.request("/owner/exports/"+id(key),"DELETE")
}
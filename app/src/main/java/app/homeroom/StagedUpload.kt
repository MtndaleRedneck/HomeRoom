package app.homeroom

import org.json.JSONObject

/**
 * RECONSTRUCTION-ORIGINAL structure (review-driven): the staged-upload record
 * and the resume completion gate as testable production logic, used by
 * AttachmentComposer. Keeps the ORIGINAL client identities across an
 * interrupted upload so a retried create/complete/post carries the same
 * idempotency keys, and refuses to enable Send until the server confirms the
 * upload complete, ready, and hash/size verified.
 *
 * NOTE: the server contract for upload create/complete itself is UNVERIFIED
 * against the owning server source (operator report pending). This pins only
 * client-side identity preservation and gating, not server behavior.
 */
internal object StagedUpload {
    data class Ids(val uploadClient:String,val completeClient:String,val postClient:String,val created:String)

    /** Identities for a composer: restored from the staged record when
     *  resuming, minted fresh only when starting (or for records saved before
     *  identities were persisted, which resumable() then rejects). */
    fun idsFor(record:JSONObject?,newId:()->String,now:()->String):Ids {
        fun pick(key:String)=record?.optString(key)?.takeIf {it.isNotEmpty()}
        return Ids(pick("upload_client") ?: newId(),
            pick("complete_client") ?: newId(),
            pick("post_client") ?: newId(),
            pick("created") ?: now())
    }

    fun newRecord(upload:String,room:String,name:String,voice:Boolean,size:Long,sha256:String,file:String,offset:Long,ids:Ids):JSONObject =
        JSONObject().put("upload",upload).put("room",room).put("name",name).put("voice",voice)
            .put("size",size).put("sha256",sha256).put("file",file).put("offset",offset)
            .put("upload_client",ids.uploadClient).put("complete_client",ids.completeClient)
            .put("post_client",ids.postClient).put("created",ids.created)

    /** True only when a stored record carries everything a VERIFIED resume
     *  needs: the full-file hash for the completion gate and the original
     *  client identities for idempotent complete/post retries. */
    fun resumable(record:JSONObject):Boolean =
        record.has("upload") && record.has("size") && record.has("sha256") &&
            record.has("upload_client") && record.has("complete_client") &&
            record.has("post_client") && record.has("created")

    /** The completion gate BOTH the fresh and the resume path must pass
     *  before Send is enabled: staged file unchanged, server state ready,
     *  server-verified hash equals the recorded hash, sizes match.
     *  Throws IllegalArgumentException with the reason on any mismatch. */
    /** Persist the attempted payload with the staged record so a process
     *  death cannot pair the ORIGINAL post identity with edited text: the
     *  record carries the payload the first attempt used, and a recreated
     *  composer restores and locks to it (release K0/T0: a retry sends
     *  "the exact same message ID and body, never an edited copy"). */
    fun recordAttempt(record:JSONObject,text:String):JSONObject = record.put("attempted_text",text)

    /** The persisted attempted payload, or null when the staged upload was
     *  never sent (or predates this field). Empty string is a real payload
     *  (message optional with an upload), so presence is keyed, not length. */
    fun attemptedText(record:JSONObject?):String? =
        if(record?.has("attempted_text")==true) record.getString("attempted_text") else null

    /** Reconciliation history: post identities rotated away after a 410, in
     *  rotation order, each with its created timestamp and attempted payload. */
    fun priorPosts(record:JSONObject?):org.json.JSONArray =
        record?.optJSONArray("prior_posts") ?: org.json.JSONArray()

    /** True when a prior rotated-away attempt may have been accepted with a
     *  lost response. That uncertainty is durable too (I2): the composer must
     *  keep local-only abandon and honest check-room messaging even though
     *  the current identity is fresh and the payload editable. */
    fun hasUnresolvedPrior(record:JSONObject?):Boolean = priorPosts(record).length()>0

    /** Durable no-remote-DELETE marker (I2): set when the server says the
     *  upload is CONSUMED but the stored event linkage cannot positively tie
     *  it to this composer's attempted post (missing event_id, nested
     *  client_id null/mismatched, or event fetch failed). Cancellation must
     *  stay local-only, and the marker survives process recreation. */
    fun markUnresolvedCancellation(record:JSONObject?):JSONObject? = record?.put("unresolved_cancel",true)
    fun unresolvedCancellationMarked(record:JSONObject?):Boolean = record?.optBoolean("unresolved_cancel",false)==true

    /** THE cancellation-safety predicate (I2: centralized so branch omissions
     *  stop recurring). Remote DELETE is safe only when NONE of the
     *  uncertainty markers hold: an unconfirmed attempted post, a rotated
     *  prior identity, or a known-consumed upload without positive linkage. */
    fun hasUnresolvedCancellation(record:JSONObject?):Boolean =
        unconfirmedAttempt(record) || hasUnresolvedPrior(record) || unresolvedCancellationMarked(record)

    /** Durable identity rotation for a 410 outgoing_expired
     *  (outgoing15.validate: retry_new_id=false, requires_user_resend=true):
     *  the explicit user resend is a NEW post with a new post identity, and
     *  the transition is persisted so process death cannot restore the
     *  expired identity. Upload identities are untouched - the upload itself
     *  is unaffected. The attempted payload is cleared: the resend is
     *  editable, and its first attempt snapshots anew. */
    fun rotateForResend(record:JSONObject?,newId:()->String,now:()->String):JSONObject? {
        if(record==null)return null
        // Preserve the outgoing identity + payload as reconciliation history
        // (I2): a 410 rejects only THIS attempt - an earlier same-ID attempt
        // may have been accepted with a lost response, and the recovery
        // lookup needs the ORIGINAL identity. Rotation must not erase it.
        val priors=record.optJSONArray("prior_posts") ?: org.json.JSONArray()
        priors.put(JSONObject()
            .put("post_client",record.optString("post_client"))
            .put("created",record.optString("created"))
            .put("attempted_text",if(record.has("attempted_text"))record.getString("attempted_text") else JSONObject.NULL))
        record.put("prior_posts",priors)
        record.put("post_client",newId()).put("created",now())
        record.remove("attempted_text")
        return record
    }

    /** True when the record carries an attempted-but-never-confirmed post:
     *  the restored composer must come back in the ambiguous state
     *  (check-room warning, retry-same-post, two-tap cancel), not a fresh
     *  Send (I2: ambiguity itself must survive restart). A confirmed post
     *  removes the record; an expired one clears attempted_text. */
    fun unconfirmedAttempt(record:JSONObject?):Boolean = attemptedText(record)!=null

    /** The event client_id the server derives for a wrapper-path post:
     *  outgoing15 namespaces to o15_+sha256(room\0client_id)[:48], then
     *  files15.post namespaces that again to fu_+sha256(room\0<o15 id>)[:48]
     *  (exact wrapper source, sections A/F). Used to tie a consumed upload's
     *  event linkage to THIS composer's intended post before asserting sent. */
    fun namespacedPostId(room:String,postClient:String):String {
        fun h(s:String)=java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        val o15="o15_"+h(room+"\u0000"+postClient).take(48)
        return "fu_"+h(room+"\u0000"+o15).take(48)
    }

    /** The text a composer post attempt must carry. After the first attempt
     *  the payload is IMMUTABLE: a retry of an ambiguous send reuses the same
     *  client identity, so it must also carry the exact same body - the
     *  release's own copy (K0/T0) says a retry sends "the exact same message
     *  ID and body, never an edited copy." The composer locks the input once
     *  posting starts; this helper is the testable invariant behind that lock. */
    fun immutableText(attempted:String?,current:String):String = attempted ?: current

    fun requireComplete(recordHash:String,total:Long,actualFileHash:String,state:String,verified:String?,size:Long) {
        require(actualFileHash==recordHash) {"staged file hash changed since staging"}
        require(state=="ready") {"server did not mark the upload ready (state="+state+")"}
        require(verified==recordHash) {"server verified hash differs from the staged file"}
        require(size==total) {"server size differs from the staged file"}
    }
}

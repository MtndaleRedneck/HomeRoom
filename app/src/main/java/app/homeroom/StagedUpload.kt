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
    fun requireComplete(recordHash:String,total:Long,actualFileHash:String,state:String,verified:String?,size:Long) {
        require(actualFileHash==recordHash) {"staged file hash changed since staging"}
        require(state=="ready") {"server did not mark the upload ready (state="+state+")"}
        require(verified==recordHash) {"server verified hash differs from the staged file"}
        require(size==total) {"server size differs from the staged file"}
    }
}

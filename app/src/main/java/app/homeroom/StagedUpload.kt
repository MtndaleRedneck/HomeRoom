package app.homeroom

import org.json.JSONArray
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

    // Pre-create record (historical local_id-keyed scheme): persisted BEFORE
    // the upload-create call, so a crash before the first response still
    // leaves a resumable record. The full create metadata (name/mime/kind via
    // mime+voice/size/sha256) and upload_client are what make a same-identity
    // recreate safe (historical held-1.5 uploads15.create UNIQUE(principal,
    // room, client) contract; live server 1.4 exposes no upload routes).
    // owner/server bind the record to the account+destination that staged it.
    fun newRecord(localId:String,room:String,name:String,mime:String,voice:Boolean,size:Long,sha256:String,file:String,ids:Ids,owner:String?,server:String?):JSONObject =
        JSONObject().put("local_id",localId).put("room",room).put("name",name).put("mime",mime).put("voice",voice)
            .put("size",size).put("sha256",sha256).put("file",file)
            .put("upload_client",ids.uploadClient).put("complete_client",ids.completeClient)
            .put("post_client",ids.postClient).put("created",ids.created)
            .put("owner",owner ?: JSONObject.NULL).put("server",server ?: JSONObject.NULL)

    // A pre-create record: never got an upload id, but carries everything a
    // same-identity recreate needs.
    fun preCreate(record:JSONObject):Boolean =
        record.has("local_id") && !record.has("upload") && record.has("sha256") &&
            record.has("mime") && record.has("upload_client") && record.has("size")

    // Account/server binding is checked separately from the in-flight
    // generation barrier (I2): a resumed composer belongs to a LATER
    // generation by definition, so generation can never gate resume - the
    // persisted binding does. Records predating binding fields stay on the
    // legacy path (hasBinding false) and are not blocked by this check.
    fun hasBinding(record:JSONObject):Boolean = record.has("owner") || record.has("server")
    fun bindingMatches(record:JSONObject,owner:String?,server:String?):Boolean {
        val boundOwner=if(record.isNull("owner")) null else record.optString("owner").takeIf {it.isNotEmpty()}
        val boundServer=if(record.isNull("server")) null else record.optString("server").takeIf {it.isNotEmpty()}
        return boundOwner==owner && boundServer==server
    }

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

/**
 * Pure staged-upload store operations (JVM-testable; Session wraps them with
 * SharedPreferences). I2 design constraints implemented here:
 * - records are keyed by the client-minted local_id (historical scheme);
 * - legacy upload-keyed records get a STABLE content-derived identity that
 *   survives delete/reorder (never index-derived); distinct records never merge;
 * - a malformed store blocks EVERY write, including single-record removal, so
 *   existing records are never silently overwritten;
 * - normalization is in-memory only until an explicit save/remove rewrites the
 *   whole array after a successful full parse, carrying ALL records.
 */
internal object StagedStore {
    class Malformed: Exception("staged_uploads malformed")

    fun parse(raw:String?):List<JSONObject>? {
        if(raw==null) return emptyList()
        return try { JSONArray(raw).objects() } catch (e:Exception) { null }
    }

    // File names are bare safe names: no dot/dotdot traversal, no separators,
    // no leading dot. An invalid stored name FAILS the restore - never mint.
    fun isValidFileName(name:String):Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name!=".." &&
            name.all {it.isLetterOrDigit() || it=='.' || it=='_' || it=='-'}

    private fun contentHash(o:JSONObject):String {
        val d=java.security.MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray(Charsets.UTF_8))
        return d.joinToString("") {"%02x".format(it)}.take(12)
    }

    // Stable identity for records predating local_id: the upload id when
    // present, else a content-derived quarantine id; collisions with an
    // existing local_id disambiguate by content hash (deterministic across
    // delete/reorder). Content-identical duplicates collapse to one identity -
    // no information loss. Quarantined records are kept verbatim and marked.
    // Order-independent identity assignment (I2): when one base id is claimed
    // by multiple DISTINCT contents - a legacy upload id duplicating an
    // existing local_id, or two different legacy records sharing an upload id -
    // EVERY claimant is suffixed with its content hash. Encounter order never
    // decides the winner, so reorder/delete cannot change a record's identity.
    // Content-identical duplicates share one identity (no information loss).
    fun normalize(records:List<JSONObject>):List<JSONObject> {
        class Meta(val base:String,val hash:String,val quarantined:Boolean,val derived:Boolean)
        val metas=records.map {r->
            val existing=r.optString("local_id").takeIf {it.isNotEmpty()}
            if(existing!=null) Meta(existing,contentHash(r),false,false)
            else {
                val upload=r.optString("upload")
                val q=upload.isEmpty()
                Meta(if(q) "quarantined-"+contentHash(r) else upload,contentHash(r),q,true)
            }
        }
        val distinctByBase=metas.groupBy {it.base}.mapValues {(_,g)->g.map {it.hash}.distinct().size}
        return records.mapIndexed {i,r->
            val m=metas[i]
            val id=if(distinctByBase.getValue(m.base)>1) m.base+"#"+m.hash else m.base
            if(!m.derived && id==m.base) r
            else {
                val out=JSONObject(r.toString()).put("local_id",id)
                if(m.quarantined) out.put("quarantined",true)
                out
            }
        }
    }

    // Save (value != null) or remove (value == null) by local_id. Throws
    // Malformed when the existing raw content cannot be fully parsed: blocked
    // writes preserve the raw store, including for single-record removal.
    fun applySave(raw:String?,id:String,value:JSONObject?):String {
        val records=parse(raw) ?: throw Malformed()
        require(value==null || value.optString("local_id")==id) {"staged record local_id mismatch"}
        val entries=normalize(records)
        val out=entries.filterNot {it.optString("local_id")==id}+listOfNotNull(value)
        return JSONArray(out).toString()
    }
}

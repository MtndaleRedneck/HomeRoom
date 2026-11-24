package app.homeroom

import android.content.Context
import android.net.Uri
import android.widget.ScrollView
import android.widget.LinearLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}

/** Private staging, bounded input and stable identities. Nothing posts until Send. */
class AttachmentComposer(context:Context,private val room:Room,private val uri:Uri,private val suppliedName:String?=null,private val voice:Boolean=false,private val resume:org.json.JSONObject?=null):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val status=text(context,"Preparing attachment...",14f,C.muted)
    private val message=input(context,"Optional message")
    // After a failed resume the button re-runs status-first recovery instead
    // of an inert post() (I2 UX residual: retry while ready=false no-ops).
    private var retryResume=false
    private val send=button(context,"Send attachment",true) {if(retryResume)resumeUpload() else post()}
    private val privateFile=File(context.cacheDir,resume?.optString("file")?.takeIf {it.matches(Regex("[A-Za-z0-9._-]+"))} ?: ("attachment-"+clientId()))
    private var job:Job?=null
    private var upload:String?=null
    private var ready=false
    private var posting=false
    private var ambiguous=false
    // Cancellation-safety state (I2: declared with the other state vars so no
    // later property initializer can overwrite the init-block restore).
    // priorUnresolved: durable uncertainty from a rotated-away prior attempt.
    // consumedUnresolved: server says consumed but linkage unproven.
    private var priorUnresolved=false
    private var consumedUnresolved=false
    // Snapshot of the payload at the first send attempt. A retry after an
    // ambiguous failure reuses postClient/created, so it must carry the exact
    // same body (release K0/T0: a retry sends "the exact same message ID and
    // body, never an edited copy"). The input locks once posting starts.
    private var attemptedText:String?=null
    // The live staged record (resume record or the one prepare() saved), so
    // the first send attempt can persist its exact payload with the staged
    // identities - process death then restores it instead of pairing the
    // original post ID with edited text.
    private var stagedRecord:JSONObject?=resume
    // Resume keeps the ORIGINAL client identities and timestamp: if the
    // interruption happened during or after complete/post, the retry carries
    // the same idempotency keys, so the server can dedup instead of doubling.
    private val stagedIds=StagedUpload.idsFor(resume,{clientId()},{java.time.Instant.now().toString()})
    // var: a 410 outgoing_expired (wrapper: requires_user_resend) rotates the
    // identity so the explicit user resend is a NEW post, not a retry.
    private var postClient=stagedIds.postClient
    private var created=stagedIds.created
    private val uploadClient=stagedIds.uploadClient
    private val completeClient=stagedIds.completeClient
    init {
        // Process-death restore: the payload a previous process attempted is
        // authoritative - prefill and lock to it (I2 retry residual).
        StagedUpload.attemptedText(resume)?.let {
            attemptedText=it;message.setText(it);message.isEnabled=false
        }
        // Uncertainty initializes from the DURABLE record BEFORE any fallible
        // work (I2 early-resume hole): a persisted attempted payload means a
        // post may already exist even if the status-first resume below never
        // completes, so cancellation must start safe, not become safe later.
        if(StagedUpload.unconfirmedAttempt(resume)) ambiguous=true
        priorUnresolved=StagedUpload.hasUnresolvedPrior(resume)
        consumedUnresolved=StagedUpload.unresolvedCancellationMarked(resume)
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,if(voice)"Voice note" else "Attach a file",22f,C.text,true));body.addView(status);body.addView(message);body.addView(send)
        send.isEnabled=false
        body.addView(button(context,"Cancel upload",false) {cancelUpload()})
    }
    override fun onShow() {if(job==null && !ready) {if(resume!=null)resumeUpload() else prepare()}}
    override fun onHide() {job?.cancel();job=null;super.onHide()}
    // RECONSTRUCTED: resume an interrupted staged upload from its local record.
    private suspend fun resumeUploadInner() {
        try {
            val record=resume ?: return
            require(StagedUpload.resumable(record)) {"staged record lacks hash or original identities"}
            upload=record.getString("upload")
            val total=record.getLong("size")
            if(!privateFile.exists() || privateFile.length()!=total) throw ApiException(0,"staged_file_missing")
            // Authoritative server status FIRST (owning source files15 GET
            // /owner/uploads/<id>): a CONSUMED upload means the post already
            // landed - reconcile as sent from the server's own record, never
            // repost, never invent success without the consumed event.
            val srv=V15Api(host.api).get("uploads/"+ApiClient.enc(upload!!))
            when(srv.optString("state")) {
                "consumed" -> {
                    val eventId=srv.optString("event_id")
                    if(eventId.isEmpty()) markConsumedUnresolved()
                    else reconcileConsumed(eventId)
                    return
                }
                "ready" -> {
                    val recordHash=record.getString("sha256")
                    val actualHash=withContext(Dispatchers.IO) {java.io.File(privateFile.absolutePath).readBytes().let {sha256(it)}}
                    StagedUpload.requireComplete(recordHash,total,actualHash,"ready",srv.optString("verified_sha256").ifEmpty {null},srv.optLong("byte_size"))
                    ready=true;send.isEnabled=true
                    restoreUnconfirmedAttempt(record)
                    return
                }
                "staged" -> {
                    // status() wire key is error_code (fail() bodies use error).
                    if(srv.optBoolean("requires_restart",false) || srv.optString("error_code")=="upload_bytes_inconsistent") {abandonCorruptUpload();return}
                }
                else -> {
                    // failed/expired/unknown state: fail closed, no resume.
                    runCatching {host.session.removeStagedUpload(upload!!)};privateFile.delete()
                    status.text="This staged upload can no longer be resumed. Cancel it and attach again."
                    return
                }
            }
            // Server-authoritative resume point (files15 status received/
            // next_offset), cross-checked against the local record.
            var offset=srv.optLong("next_offset",record.optLong("offset",0L))
            require(offset in 0..total)
            withContext(Dispatchers.IO) {java.io.RandomAccessFile(privateFile,"r").use {input->
                while(offset<total) {
                    ensureActive();input.seek(offset);val chunk=ByteArray(minOf(262144L,total-offset).toInt());input.readFully(chunk)
                    val next=host.api.uploadChunk(upload!!,offset,chunk,sha256(chunk)).getLong("next_offset")
                    require(next==offset+chunk.size);offset=next
                    record.put("offset",offset);host.session.saveStagedUpload(record)
                    withContext(Dispatchers.Main) {status.text="Uploading: "+offset+" / "+total+" bytes"}
                }
            }}
            // Same completion gate as the fresh path: the server must confirm
            // the upload complete, ready, full-hash and size verified before
            // Send is enabled. complete carries the original completeClient id.
            val recordHash=record.getString("sha256")
            val actualHash=withContext(Dispatchers.IO) {java.io.File(privateFile.absolutePath).readBytes().let {sha256(it)}}
            val completeJson=V15Api(host.api).mutate("uploads/"+ApiClient.enc(upload!!)+"/complete",JSONObject().put("client_id",completeClient))
            if(markConsumedIfPosted(completeJson))return
            val complete=UploadStatus.from(completeJson)
            StagedUpload.requireComplete(recordHash,total,actualHash,complete.state,complete.verified,complete.size)
            ready=true;send.isEnabled=true
            restoreUnconfirmedAttempt(record)
        }catch(e:CancellationException) {throw e}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(e:ApiException) {
            // cp42 crash recovery (owning source): upload_bytes_inconsistent
            // means requires_restart - never auto-resume the same upload.
            if(e.code=="upload_bytes_inconsistent")abandonCorruptUpload()
            else resumeFailed()
        }
        catch(_:Exception) {resumeFailed()}
    }
    // Status-first resume failed BEFORE any ready/complete recovery (I2
    // early-resume hole): when the durable record carries ANY uncertainty
    // marker the upload may already be posted - come back ambiguous
    // (check-room, retry-same-post, two-tap local-only cancel). Only a
    // marker-free record gets the plain cannot-resume message.
    private fun resumeFailed() {
        if(stagedRecord?.let {StagedUpload.hasUnresolvedCancellation(it)}==true) {
            // Wire the retry to recovery, not an inert post: ready is false,
            // so the ambiguous retry must re-run the status-first resume.
            retryResume=true;setAmbiguous();send.text="Retry recovery"
            return
        }
        status.text="This staged upload can no longer be resumed. Cancel it and attach again."
    }
    // Same-ID complete replay can return CONSUMED with linkage + replayed
    // (uploads15.status exact source): the earlier complete AND its post both
    // landed. Reconcile as sent from the server's own record - never repost,
    // and never claim sent without the consumed event linkage.
    private suspend fun markConsumedIfPosted(complete:JSONObject):Boolean {
        if(complete.optString("state")!="consumed")return false
        val eventId=complete.optString("event_id")
        if(eventId.isEmpty()) {markConsumedUnresolved();return true}
        reconcileConsumed(eventId)
        return true
    }
    // Server-reported CONSUMED without usable linkage (no event_id, or the
    // event's nested client_id does not match this attempted post): uncertain
    // remote state, so the no-remote-DELETE marker is DURABLE (I2) and
    // cancellation stays local-only across process recreation. One helper, so
    // every consumed-with-doubt branch shares the same transition.
    private fun markConsumedUnresolved() {
        ready=false;send.isEnabled=false;consumedUnresolved=true
        stagedRecord?.let {host.session.saveStagedUpload(StagedUpload.markUnresolvedCancellation(it)!!)}
        status.text="Upload already used. Check the room before sending again."
    }
    // Tie the consumed linkage to THIS composer's intended post before the UI
    // asserts sent (I2): the event's client_id must equal the deterministic
    // double-namespaced ID (outgoing15 o15_ then files15 fu_). Any doubt ->
    // neutral copy, no claimed success, no repost.
    private fun resumeUpload() {retryResume=false;job=screenScope.launch {resumeUploadInner()}}
    private suspend fun reconcileConsumed(eventId:String) {
        ready=false;send.isEnabled=false
        val linked=try {
            // Owning contract (owner_room single-event handler + owner_event):
            // {"event": {..., "client_id": <stored inner client or null>}}.
            // Nested key ONLY - no top-level fallback exists in the contract;
            // null/missing fails closed.
            val ev=host.api.event(eventId)
            val cid=ev.optJSONObject("event")?.optString("client_id") ?: ""
            cid.isNotEmpty() && cid==StagedUpload.namespacedPostId(room.id,postClient)
        } catch(e:SessionEndedException) {throw e}
        catch(_:Exception) {false}
        if(linked) {
            upload?.let {u->runCatching {host.session.removeStagedUpload(u)}};privateFile.delete()
            status.text="Attachment sent. Server recorded this post."
        } else markConsumedUnresolved()
    }
    // Server-side byte inconsistency (cp42): the staged upload is abandoned -
    // record and staged file removed, Send stays locked, release e1 wording.
    private fun abandonCorruptUpload() {
        upload?.let {u->runCatching {host.session.removeStagedUpload(u)}}
        privateFile.delete();ready=false;send.isEnabled=false
        status.text="This upload was interrupted and cannot resume. Cancel it, then choose the file again to start a new upload. No message was sent."
    }
    private fun prepare() {job=screenScope.launch {
        try {
            var name=suppliedName
            if(name==null)context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())name=it.getString(0)}
            val safeName=(name ?: "attachment").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"),"_").take(180)
            val mime=if(voice)"audio/mp4" else context.contentResolver.getType(uri) ?: "application/octet-stream"
            val limit=if(voice)10L*1024*1024 else 25L*1024*1024
            val metadata=withContext(Dispatchers.IO) {
                val digest=MessageDigest.getInstance("SHA-256");var size=0L
                context.contentResolver.openInputStream(uri)!!.use {input->privateFile.outputStream().use {out->val b=ByteArray(65536);while(true) {ensureActive();val n=input.read(b);if(n<0)break;size+=n;require(size<=limit);out.write(b,0,n);digest.update(b,0,n)}}}
                require(size>0)
                size to digest.digest().joinToString("") {"%02x".format(it)}
            }
            val api=V15Api(host.api)
            val result=api.mutate("rooms/"+ApiClient.enc(room.id)+"/uploads",JSONObject().put("client_id",uploadClient).put("name",safeName).put("mime_type",mime).put("byte_size",metadata.first).put("sha256",metadata.second).put("kind",if(voice)"voice" else if(mime.startsWith("image/"))"image" else "file"))
            upload=result.getString("upload_id")
            val record=StagedUpload.newRecord(upload!!,room.id,safeName,voice,metadata.first,metadata.second,privateFile.name,result.getLong("next_offset"),stagedIds)
            stagedRecord=record
            host.session.saveStagedUpload(record)
            var offset=result.getLong("next_offset")
            withContext(Dispatchers.IO) {java.io.RandomAccessFile(privateFile,"r").use {input->
                while(offset<metadata.first) {
                    ensureActive();input.seek(offset);val chunk=ByteArray(minOf(262144L,metadata.first-offset).toInt());input.readFully(chunk)
                    val next=host.api.uploadChunk(upload!!,offset,chunk,sha256(chunk)).getLong("next_offset")
                    require(next==offset+chunk.size);offset=next
                    record.put("offset",offset);host.session.saveStagedUpload(record)
                    withContext(Dispatchers.Main) {status.text="Uploading: "+offset+" / "+metadata.first+" bytes"}
                }
            }}
            val completeJson=api.mutate("uploads/"+ApiClient.enc(upload!!)+"/complete",JSONObject().put("client_id",completeClient))
            if(markConsumedIfPosted(completeJson))return@launch
            val complete=UploadStatus.from(completeJson)
            StagedUpload.requireComplete(metadata.second,metadata.first,metadata.second,complete.state,complete.verified,complete.size)
            ready=true;send.isEnabled=true
            status.text=safeName+" · uploaded and checksum verified. Not sent yet."+(if(voice)" Voice transcription may be unavailable." else "")
        }catch(e:CancellationException) {throw e}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(e:ApiException) {if(e.code=="upload_bytes_inconsistent")abandonCorruptUpload()
            else status.text="Upload not confirmed. Cancel and check the room before starting again."}
        catch(_:Exception) {status.text="Upload not confirmed. Cancel and check the room before starting again."}
    }}
    private fun post() {
        if(!ready || posting)return
        val text=StagedUpload.immutableText(attemptedText,message.text.toString())
        if(text.length>8000) {message.error="Up to 8000 characters";return}
        attemptedText=text
        // DURABILITY GATE: the attempted payload must reach persistent storage
        // (checked commit) before any network side effect. On failure: no POST,
        // user-visible error, composer stays editable for an explicit retry.
        // NOTE: a failed commit() may leave the in-memory preference map ahead
        // of disk; a later successful save rewrites the whole map, healing the
        // divergence, and process death restores the last durable state - the
        // POST only ever runs after a successful durable write of THIS record.
        if(stagedRecord!=null) {
            try {host.session.saveStagedUpload(StagedUpload.recordAttempt(stagedRecord!!,text))}
            catch(e:Exception) {
                attemptedText=null
                status.text="Couldn't durably record this send. Nothing was sent - try again."
                return
            }
        }
        posting=true;send.isEnabled=false
        message.isEnabled=false
        screenScope.launch {
            try {
                host.api.postWithUploads(room.id,postClient,created,text,room.mainThreadKey,null,emptyList(),listOf(upload!!))
                if(upload!=null)runCatching {host.session.removeStagedUpload(upload!!)}
                status.text="Attachment sent. Server recorded this post.";ready=false;privateFile.delete();send.visibility=android.view.View.GONE
            }catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(e:ApiException) {
                if(e.code=="outgoing_expired") {
                    // outgoing15.validate, hardcoded per contract (ApiException
                    // carries only status/code/retryAfter - the body fields
                    // retry_new_id=false/requires_user_resend=true are NOT
                    // dynamically read). The 410 rejects THIS attempt; an
                    // earlier same-ID attempt may still have landed (lost
                    // response), so the copy preserves that uncertainty and
                    // sends the owner to check the room first. The resend is
                    // a NEW explicit post, never a silent identity swap, and
                    // the rotation is PERSISTED so process death cannot
                    // restore the expired identity.
                    ambiguous=false;attemptedText=null;priorUnresolved=true
                    val fresh=clientId();val now=java.time.Instant.now().toString()
                    postClient=fresh;created=now
                    val rotated=stagedRecord?.let {StagedUpload.rotateForResend(it,{fresh},{now})}
                    if(rotated!=null && runCatching {host.session.saveStagedUpload(rotated)}.isFailure) {
                        // The expired identity could not be durably rotated: keep the
                        // resend DISABLED (a resend now would risk losing the prior
                        // identity on crash) and fail loudly instead of proceeding.
                        message.isEnabled=false;send.isEnabled=false
                        status.text="Couldn't durably record the expired send. Restart the app, then check the room before sending again."
                        return@launch
                    }
                    // Keep the in-memory record identical to the durably rotated one:
                    // otherwise a later resend would persist the PRE-rotation record
                    // (same upload ID) and silently undo prior_posts before a POST
                    // under the new identity. Only assign AFTER the durable save.
                    if(rotated!=null)stagedRecord=rotated
                    message.isEnabled=true;send.text="Send attachment";send.isEnabled=true
                    status.text="This send wasn't confirmed and has expired. Check the room, then review and send again."
                } else if(e.code=="upload_not_ready") {
                    // files15.post rejects a non-ready upload - including one
                    // CONSUMED by an earlier successful post. Status-first
                    // recovery (I2), never an unchanged-retry trap.
                    val srv=try {V15Api(host.api).get("uploads/"+ApiClient.enc(upload!!))}
                        catch(x:SessionEndedException) {throw x}
                        catch(_:Exception) {null}
                    when {
                        srv==null -> setAmbiguous()
                        srv.optString("state")=="consumed" -> {
                            val eventId=srv.optString("event_id")
                            if(eventId.isEmpty()) markConsumedUnresolved()
                            else reconcileConsumed(eventId)
                        }
                        srv.optBoolean("requires_restart",false) || srv.optString("error_code")=="upload_bytes_inconsistent" -> abandonCorruptUpload()
                        else -> setAmbiguous()
                    }
                } else setAmbiguous()
            }
            catch(_:Exception) {setAmbiguous()}
            finally {posting=false}
        }
    }
    // Ambiguity restored across restart (I2): a persisted attempted payload
    // means the post outcome was NEVER confirmed - come back in the ambiguous
    // state (check-room warning, retry-same-post, two-tap cancel), not a
    // fresh Send.
    private fun restoreUnconfirmedAttempt(record:JSONObject) {
        if(StagedUpload.unconfirmedAttempt(record)) {setAmbiguous();return}
        // A durable consumed-without-linkage marker contradicts a plain ready
        // state: fail closed, never claim ready (I2 central predicate).
        if(consumedUnresolved) {markConsumedUnresolved();return}
        if(priorUnresolved) {
            // Honest ready state: an earlier rotated-away send may already be
            // in the room (410 rejects one attempt; it cannot prove the
            // earlier one absent). Editable new post, but check-room first.
            status.text="Ready to send. An earlier send may already be in the room - check it first."
            return
        }
        status.text="Ready to send. Upload completed and checksum verified."
    }
    private fun setAmbiguous() {
        ambiguous=true
        status.text="Send not confirmed. Check the room first. Retry keeps the exact same post."
        send.text="Retry same post";send.isEnabled=true
    }
    // Two-tap cancel while ambiguous (release K0/W0 semantics: its own copy
    // contemplates both cancelling and sending again AFTER checking the
    // room). First tap warns, second proceeds - an unrepairable retry can no
    // longer trap the owner, without inventing a no-post status (I2).
    private var cancelWarned=false
    // Centralized cancellation safety (I2): remote DELETE only when no
    // uncertainty marker is set. Consults the DURABLE record predicate, not
    // just in-session flags, so a flag not yet restored (early-resume
    // failure before ready/complete) cannot reopen plain remote DELETE.
    private fun cancelUnsafe()=ambiguous || priorUnresolved || consumedUnresolved || StagedUpload.hasUnresolvedCancellation(stagedRecord)
    private fun cancelUpload() {
        if(posting)return
        if(cancelUnsafe() && !cancelWarned) {cancelWarned=true;status.text="A post may already exist. Check the room before cancelling or sending again.";return}
        job?.cancel();ready=false;send.isEnabled=false
        val id=upload
        if(id==null) {privateFile.delete();status.text="Local upload cancelled.";return}
        if(cancelUnsafe()) {
            // I2: DELETE on a possibly-CONSUMED upload has no grounded effect
            // semantics - never delete a remote attachment blindly. Local
            // abandon only; an unposted staged upload expires server-side
            // (release X0 copy), a landed post is managed from the room.
            upload?.let {u->runCatching {host.session.removeStagedUpload(u)}}
            privateFile.delete()
            status.text="Cancelled locally, server not contacted. Check the room: a recorded post stays there."
            return
        }
        privateFile.delete()
        screenScope.launch {
            try {host.api.request("/owner/uploads/"+ApiClient.enc(id),"DELETE");status.text="Upload cancelled. No post was sent."}
            catch(_:Exception) {status.text="Stopped locally. Server cleanup not confirmed; unused uploads expire."}
        }
    }
}

// Actual original source (restart patch read Nov 11 23:30:32 EST):
// only a 409 upload_bytes_inconsistent forces a fresh upload.
internal fun uploadMustRestart(error:ApiException)=error.status==409 && error.code=="upload_bytes_inconsistent"

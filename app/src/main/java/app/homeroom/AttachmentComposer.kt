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
    private val send=button(context,"Send attachment",true) {post()}
    private val privateFile=File(context.cacheDir,resume?.optString("file")?.takeIf {it.matches(Regex("[A-Za-z0-9._-]+"))} ?: ("attachment-"+clientId()))
    private var job:Job?=null
    private var upload:String?=null
    private var ready=false
    private var posting=false
    private var ambiguous=false
    // Resume keeps the ORIGINAL client identities and timestamp: if the
    // interruption happened during or after complete/post, the retry carries
    // the same idempotency keys, so the server can dedup instead of doubling.
    private val uploadClient=resume?.optString("upload_client")?.takeIf {it.isNotEmpty()} ?: clientId()
    private val completeClient=resume?.optString("complete_client")?.takeIf {it.isNotEmpty()} ?: clientId()
    private val postClient=resume?.optString("post_client")?.takeIf {it.isNotEmpty()} ?: clientId()
    private val created=resume?.optString("created")?.takeIf {it.isNotEmpty()} ?: java.time.Instant.now().toString()
    init {
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,if(voice)"Voice note" else "Attach a file",22f,C.text,true));body.addView(status);body.addView(message);body.addView(send)
        send.isEnabled=false
        body.addView(button(context,"Cancel upload",false) {cancelUpload()})
    }
    override fun onShow() {if(job==null && !ready) {if(resume!=null)resumeUpload() else prepare()}}
    override fun onHide() {job?.cancel();job=null;super.onHide()}
    // RECONSTRUCTED: resume an interrupted staged upload from its local record.
    private fun resumeUpload() {job=screenScope.launch {
        try {
            val record=resume ?: return@launch
            upload=record.getString("upload")
            val total=record.getLong("size")
            if(!privateFile.exists() || privateFile.length()!=total) throw ApiException(0,"staged_file_missing")
            var offset=record.optLong("offset",0L)
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
            require(actualHash==recordHash) {"staged file hash changed since staging"}
            val complete=UploadStatus.from(V15Api(host.api).mutate("uploads/"+ApiClient.enc(upload!!)+"/complete",JSONObject().put("client_id",completeClient)))
            require(complete.state=="ready" && complete.verified==recordHash && complete.size==total)
            ready=true;status.text="Ready to send. Upload completed and checksum verified.";send.isEnabled=true
        }catch(e:CancellationException) {throw e}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {status.text="This staged upload can no longer be resumed. Cancel it and attach again."}
    }}
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
            val record=JSONObject().put("upload",upload).put("room",room.id).put("name",safeName).put("voice",voice).put("size",metadata.first).put("sha256",metadata.second).put("file",privateFile.name).put("offset",result.getLong("next_offset")).put("upload_client",uploadClient).put("complete_client",completeClient).put("post_client",postClient).put("created",created)
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
            val complete=UploadStatus.from(api.mutate("uploads/"+ApiClient.enc(upload!!)+"/complete",JSONObject().put("client_id",completeClient)))
            require(complete.state=="ready" && complete.verified==metadata.second && complete.size==metadata.first)
            ready=true;send.isEnabled=true
            status.text=safeName+" · uploaded and checksum verified. Not sent yet."+(if(voice)" Voice transcription may be unavailable." else "")
        }catch(e:CancellationException) {throw e}
        catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
        catch(_:Exception) {status.text="Upload not confirmed. Cancel and check the room before starting again."}
    }}
    private fun post() {
        if(!ready || posting)return
        if(message.text.length>8000) {message.error="Up to 8000 characters";return}
        posting=true;send.isEnabled=false
        if(!ambiguous)message.isEnabled=false
        screenScope.launch {
            try {
                host.api.postWithUploads(room.id,postClient,created,message.text.toString(),room.mainThreadKey,null,emptyList(),listOf(upload!!))
                if(upload!=null)host.session.removeStagedUpload(upload!!)
                status.text="Attachment sent. Server recorded this post.";ready=false;privateFile.delete();send.visibility=android.view.View.GONE
            }catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {ambiguous=true;status.text="Send not confirmed. Check the room first. Retry keeps the exact same post.";send.text="Retry same post";send.isEnabled=true}
            finally {posting=false}
        }
    }
    private fun cancelUpload() {
        if(posting || ambiguous) {status.text="A post may already exist. Check the room before cancelling or sending again.";return}
        job?.cancel();privateFile.delete();ready=false;send.isEnabled=false
        val id=upload
        if(id==null) {status.text="Local upload cancelled.";return}
        screenScope.launch {
            try {host.api.request("/owner/uploads/"+ApiClient.enc(id),"DELETE");status.text="Upload cancelled. No post was sent."}
            catch(_:Exception) {status.text="Stopped locally. Server cleanup not confirmed; unused uploads expire."}
        }
    }
}
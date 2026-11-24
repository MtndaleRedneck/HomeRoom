package app.homeroom

import android.content.Context
import android.widget.ScrollView
import kotlinx.coroutines.*
import java.io.File

class VerifiedDownloadScreen(context:Context,private val name:String,private val path:String,private val size:Long,private val digest:String?):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val status=text(context,"Not started",15f,C.muted)
    private val local=File(context.cacheDir,"download-"+clientId())
    private var job:Job?=null
    private var complete=false
    init {
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,name,20f,C.text,true));body.addView(status)
        body.addView(text(context,if(digest==null)"Legacy file: no published checksum. Integrity cannot be verified." else "Size and SHA-256 will be checked before opening.",14f,if(digest==null)C.amber else C.muted))
        body.addView(button(context,"Download",true) {start()})
        body.addView(button(context,"Cancel",false) {job?.cancel();job=null;local.delete();complete=false;status.text="Download cancelled. Partial local data removed."})
        body.addView(button(context,"Open completed file",false) {
            if(!complete) {snack(this,"Download must finish first.");return@button}
            val folder=File(context.cacheDir,"shared-downloads").apply {mkdirs()}
            val target=File(folder,name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"),"_").ifBlank {"file"})
            local.copyTo(target,true)
            val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",target)
            try {host.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,context.contentResolver.getType(uri) ?: "application/octet-stream").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),"Open file"))}
            catch(_:Exception) {snack(this,"No app can open this file.")}
        })
    }
    private fun start() {
        if(job?.isActive==true || complete)return
        job=screenScope.launch {
            try {
                val verified=host.api.verifiedDownload(path,local,size,digest) {done,total->host.runOnUiThread {status.text="Downloading: $done / $total bytes"}}
                complete=true;status.text=if(verified)"Download complete. Size and checksum match." else "Download complete. Size matches; checksum unavailable."
            }catch(e:CancellationException) {throw e}
            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {status.text="Download failed or integrity check failed. No completed file is available."}
        }
    }
    override fun onHide() {job?.cancel();job=null;if(!complete)local.delete();super.onHide()}
}

class BundleExportScreen(context:Context,private val room:Room):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val status=text(context,"Readable text/HTML plus attachments, up to the server's limit. Nothing is shared publicly.",15f,C.muted)
    private val id=clientId()
    private var exportId:String?=null
    private var job:Job?=null
    init {
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,"Export room with attachments",22f,C.text,true));body.addView(status)
        body.addView(button(context,"Prepare export",true) {start()})
        body.addView(button(context,"Cancel export",false) {job?.cancel();job=null;screenScope.launch {
            try {exportId?.let {V15Api(host.api).cancelExport(it)};status.text="Export cancellation recorded."}
            catch(_:Exception) {status.text="Stopped checking. Server cancellation not confirmed; private export expires."}
        }})
    }
    private fun start() {
        if(job?.isActive==true)return
        job=screenScope.launch {
            try {
                val api=V15Api(host.api)
                if(exportId==null)exportId=api.export(room.id,id).getString("job_id")
                while(true) {
                    val state=api.exportStatus(exportId!!)
                    val kind=state.getString("state")
                    status.text=kind+" · "+state.optLong("files_done")+" files · "+state.optLong("bytes_done")+" source bytes"
                    if(kind in listOf("ready","ready_with_omissions")) {
                        val bundle=state.getJSONObject("bundle");val digest=UpdateCheck.parseDigest(bundle.getString("sha256")) ?: throw java.io.IOException("no digest")
                        if(kind=="ready_with_omissions")body.addView(text(context,"Incomplete export: "+state.optJSONArray("missing_files")+"\n"+state.optJSONArray("omitted_files"),14f,C.amber))
                        val path="/owner/exports/"+ApiClient.enc(exportId!!)+"/download"
                        body.addView(button(context,"Download "+if(kind=="ready_with_omissions")"incomplete bundle" else "complete bundle",false) {host.openPage("Download export",VerifiedDownloadScreen(context,"HomeRoom-export.zip",path,bundle.getLong("byte_size"),digest))})
                        break
                    }
                    if(kind in listOf("failed","cancelled"))break
                    delay(1500)
                }
            }catch(e:CancellationException) {throw e}
            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {status.text="Export not confirmed. Refresh this request; no partial export was called complete."}
        }
    }
    override fun onHide() {job?.cancel();job=null;super.onHide()}
}
package app.homeroom

import android.Manifest
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/** Delegates authenticated GETs to Android's public Downloads service. */
object FileDownloads {
    private val pending=mutableSetOf<Long>()
    private var receiver:BroadcastReceiver?=null
    fun download(host:MainActivity,file:RoomFile) {
        if(Build.VERSION.SDK_INT<29 && ContextCompat.checkSelfPermission(host,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(host,arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),702)
            snack(host.window.decorView,"Allow Downloads access, then tap the file again.")
            return
        }
        val base=host.session.serverUrl?.trimEnd('/') ?: return
        val cookie=host.session.cookie ?: return
        val manager=host.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if(receiver==null) {
            receiver=object:BroadcastReceiver() {
                override fun onReceive(context:Context,intent:Intent) {
                    val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)
                    if(!pending.remove(id))return
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if(!cursor.moveToFirst())return
                        if(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL) {
                            snack(host.window.decorView,"Download didn't finish. Tap the file to retry.");return
                        }
                    }
                    val uri=manager.getUriForDownloadedFile(id) ?: return
                    val mime=manager.getMimeTypeForDownloadedFile(id) ?: "application/octet-stream"
                    try {host.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Open file"))}
                    catch(_:ActivityNotFoundException) {snack(host.window.decorView,"Saved in Downloads. No app can open this file.")}
                }
            }
            ContextCompat.registerReceiver(host,receiver,IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),ContextCompat.RECEIVER_EXPORTED)
        }
        val name=file.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"),"_").ifBlank {"HomeRoom-file"}
        try {
            val request=DownloadManager.Request(Uri.parse(base+ApiClient.API_PREFIX+"/owner/files/"+Uri.encode(file.id)))
                .addRequestHeader("Cookie",ApiClient.COOKIE_NAME+"="+cookie)
                .addRequestHeader("Origin",base)
                .addRequestHeader("Sec-Fetch-Site","same-origin")
                .addRequestHeader("Sec-Fetch-Mode","cors")
                .addRequestHeader("Sec-Fetch-Dest","empty")
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name)
                .setAllowedOverMetered(true)
            pending.add(manager.enqueue(request))
            snack(host.window.decorView,"Downloading to Downloads")
        } catch(_:Exception) {snack(host.window.decorView,"Couldn't start the download. Tap to retry.")}
    }
    fun close(host:MainActivity) {receiver?.let {try {host.unregisterReceiver(it)} catch(_:Exception) {}};receiver=null;pending.clear()}
}
package app.homeroom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks the project's GitHub releases and installs a newer version in the app.
 * No dialogs: everything shows inline on the Settings > Updates row.
 * The download uses its own plain HTTPS client: no HomeRoom login or cookies ever go to GitHub.
 */
object UpdateCheck {
    enum class State {IDLE,CHECKING,UP_TO_DATE,AVAILABLE,DOWNLOADING,NEEDS_PERMISSION,CHECK_FAILED,DOWNLOAD_FAILED}

    private const val LATEST_RELEASE_API="https://api.github.com/repos/MtndaleRedneck/HomeRoom/releases/latest"
    private val client by lazy {OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()}

    @Volatile var state=State.IDLE
        private set
    @Volatile var progress=0
        private set
    var latestTag:String?=null
    var checkedAt:String?=null
        private set
    private var latestApk:String?=null
    private var latestDigest:String?=null
    private var busy=false

    fun hasUpdate()=latestTag?.let {isNewer(it,BuildConfig.VERSION_NAME)} ?: false
    /** "v1.3" and "1.3" both read as "1.3". */
    fun displayVersion(tag:String)=tag.removePrefix("v").removePrefix("V")

    private fun setState(s:State,p:Int=progress) {state=s;progress=p;MainActivity.onUpdateChanged()}

    /** Silent check on app open. Never shows anything; the Settings row and badge update. */
    fun checkInBackground(scope:CoroutineScope) {
        if(busy||state==State.DOWNLOADING)return
        busy=true
        scope.launch {
            try {
                val r=fetch()
                if(r!=null) apply(r) else if(state==State.CHECKING) setState(State.CHECK_FAILED)
            } finally {busy=false}
        }
    }

    /** Opens the installer flow for the latest release when one is available. */
    fun openLatest(activity:Activity,scope:CoroutineScope):Boolean {
        if(!hasUpdate())return false
        install(activity,scope)
        return true
    }

    /** Manual check from Settings: reports a short outcome message. */
    fun checkManually(activity:Activity,scope:CoroutineScope,session:Session,onMessage:(String)->Unit) {
        if(busy)return
        busy=true
        setState(State.CHECKING)
        scope.launch {
            try {
                val r=fetch()
                if(r==null) {setState(State.CHECK_FAILED);onMessage("Update check failed. Try again later.")}
                else {
                    apply(r)
                    if(hasUpdate()) {onMessage("Update "+r.tag+" available.");install(activity,scope)}
                    else onMessage("HomeRoom is up to date.")
                }
            } finally {busy=false}
        }
    }

    /** Row tapped: check, and if there is an update, download and install it. */
    fun onRowTapped(activity:Activity,scope:CoroutineScope) {
        when(state) {
            State.CHECKING,State.DOWNLOADING -> return
            State.AVAILABLE,State.NEEDS_PERMISSION,State.DOWNLOAD_FAILED -> if(hasUpdate()) {install(activity,scope);return}
            else -> {}
        }
        if(busy)return
        busy=true;setState(State.CHECKING)
        scope.launch {
            try {
                val r=fetch()
                if(r==null) setState(State.CHECK_FAILED) else apply(r)
            } finally {busy=false}
        }
    }

    private fun apply(r:Release) {
        latestTag=r.tag;latestApk=r.apk;latestDigest=r.sha256
        checkedAt=java.time.Instant.now().toString()
        setState(if(isNewer(r.tag,BuildConfig.VERSION_NAME)) State.AVAILABLE else State.UP_TO_DATE)
    }

    private data class Release(val tag:String,val apk:String?,val sha256:String?)

    private suspend fun fetch():Release? = withContext(Dispatchers.IO) {
        try {
            val req=Request.Builder().url(LATEST_RELEASE_API).header("Accept","application/vnd.github+json").header("User-Agent","HomeRoom-Android").build()
            client.newCall(req).execute().use {resp ->
                if(!resp.isSuccessful) return@withContext null
                val release=JSONObject(resp.body!!.string())
                val tag=release.optString("tag_name")
                if(tag.isEmpty()) return@withContext null
                val asset=release.optJSONArray("assets")?.objects()?.firstOrNull {it.optString("name").endsWith(".apk",true)}
                Release(tag,asset?.nullableString("browser_download_url"),parseDigest(asset?.nullableString("digest")))
            }
        } catch(e:Exception) {null}
    }

    /** Download, check, then hand to Android's own installer (which also enforces the same signer). */
    private fun install(activity:Activity,scope:CoroutineScope) {
        val url=latestApk
        if(url==null || !isTrustedDownloadUrl(url)) {setState(State.DOWNLOAD_FAILED);return}
        if(!activity.packageManager.canRequestPackageInstalls()) {
            setState(State.NEEDS_PERMISSION)
            try {activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.packageName)))}
            catch(_:Exception) {}
            return
        }
        if(busy)return
        busy=true;setState(State.DOWNLOADING,0)
        scope.launch {
            try {
                val file=withContext(Dispatchers.IO) {download(activity.applicationContext,url)}
                if(file==null) {setState(State.DOWNLOAD_FAILED);return@launch}
                setState(State.AVAILABLE)
                val uri=FileProvider.getUriForFile(activity,activity.packageName+".files",file)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch(e:Exception) {setState(State.DOWNLOAD_FAILED)}
            finally {busy=false}
        }
    }

    private fun download(context:Context,url:String):File? {
        val dir=File(context.cacheDir,"updates").apply {mkdirs()}
        dir.listFiles()?.forEach {it.delete()}
        val target=File(dir,"HomeRoom-update.apk")
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent","HomeRoom-Android").build()).execute().use {resp ->
                if(!resp.isSuccessful) return null
                if(!isTrustedDownloadUrl(resp.request.url.toString())) return null
                val body=resp.body ?: return null
                val total=body.contentLength()
                val sha=MessageDigest.getInstance("SHA-256")
                var done=0L;var lastPct=-1
                body.byteStream().use {input ->
                    target.outputStream().use {out ->
                        val buf=ByteArray(64*1024)
                        while(true) {
                            val n=input.read(buf);if(n<0)break
                            out.write(buf,0,n);sha.update(buf,0,n);done+=n
                            if(total>0) {val pct=(done*100/total).toInt();if(pct!=lastPct) {lastPct=pct;progress=pct;MainActivity.onUpdateChanged()}}
                        }
                    }
                }
                val expected=latestDigest
                if(expected!=null && !sha.digest().joinToString("") {"%02x".format(it)}.equals(expected,true)) {target.delete();return null}
            }
            if(!isOurNextVersion(context,target)) {target.delete();return null}
            return target
        } catch(e:Exception) {target.delete();return null}
    }

    /** The file must be this app, newer than what is installed, and signed by the same key. */
    @Suppress("DEPRECATION")
    private fun isOurNextVersion(context:Context,file:File):Boolean {
        val pm=context.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: return false
        val own=pm.getPackageInfo(context.packageName,flags)
        if(archive.packageName!=context.packageName) return false
        if(archive.longVersionCodeCompat()<=own.longVersionCodeCompat()) return false
        fun sigs(i:android.content.pm.PackageInfo):Set<String> {
            val arr=if(Build.VERSION.SDK_INT>=28) i.signingInfo?.apkContentsSigners else i.signatures
            return arr?.map {s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {"%02x".format(it)}}?.toSet() ?: emptySet()
        }
        val a=sigs(archive);return a.isNotEmpty() && a==sigs(own)
    }
    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat():Long=if(Build.VERSION.SDK_INT>=28) longVersionCode else versionCode.toLong()

    /** Only GitHub, only HTTPS. */
    internal fun isTrustedDownloadUrl(url:String):Boolean {
        val u=try {java.net.URI(url)} catch(_:Exception) {return false}
        val host=u.host?.lowercase() ?: return false
        return u.scheme=="https" && (host=="github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
    }
    // Actual original source (UpdateCheck.kt read Nov 11 08:57:39 EST).
    internal fun digestMatches(expected:String?,actual:String):Boolean = expected!=null && expected.equals(actual,true)
    internal fun parseDigest(d:String?):String? = d?.removePrefix("sha256:")?.takeIf {it.length==64 && it.all {c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F'}}

    internal fun isNewer(tag:String,current:String):Boolean {
        fun parts(v:String):List<Int> = v.removePrefix("v").split('.','-').mapNotNull {it.takeWhile {c -> c.isDigit()}.toIntOrNull()}
        val a=parts(tag);val b=parts(current)
        for(i in 0 until maxOf(a.size,b.size)) {
            val x=a.getOrElse(i) {0};val y=b.getOrElse(i) {0}
            if(x!=y) return x>y
        }
        return false
    }
}
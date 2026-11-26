package app.homeroom

import android.content.Context
import android.widget.ScrollView

/** Local, bounded stack only. Exception messages can contain private text, so omit them. */
object CrashLog {
    fun install(context:Context) {
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler {thread,error ->
            try {
                val frames=error.stackTrace.take(40).joinToString("\n") {"${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"}
                context.openFileOutput("last-crash.txt",Context.MODE_PRIVATE).use {it.write(("HomeRoom "+BuildConfig.VERSION_NAME+"\n"+java.time.Instant.now()+"\n"+error.javaClass.name+"\n"+frames).toByteArray())}
            } catch(_:Exception) {}
            previous?.uncaughtException(thread,error)
        }
    }
    // RECONSTRUCTED Nov 24: exact historical Nov 11 body (operator
    // model-helper source attachment, parsed as data). Class names + stack
    // frames only - never exception messages, which can carry private text.
    internal fun safeDetails(error:Throwable):String {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        val result=StringBuilder();var cause:Throwable?=error;var count=0
        while(cause!=null && seen.add(cause) && count++<6) {
            result.append(if(count==1) "" else "Caused by: ").append(cause.javaClass.name).append('\n')
            cause.stackTrace.take(30).forEach {result.append(it.className).append('.').append(it.methodName).append('(').append(it.fileName).append(':').append(it.lineNumber).append(")\n")}
            cause=cause.cause
        }
        return result.toString().take(12000)
    }
    fun read(context:Context)=try {context.openFileInput("last-crash.txt").bufferedReader().use {it.readText().take(12000)}} catch(_:Exception) {"No crash recorded on this phone."}
}
class HomeRoomApplication:android.app.Application() {
    override fun onCreate() {super.onCreate();CrashLog.install(this)}
}
class CrashLogScreen(context:Context):Screen(context) {
    init {
        val col=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
        addView(ScrollView(context).apply {addView(col)},LayoutParams(-1,-1))
        val preview=text(context,CrashLog.read(context),14f,C.text).apply {setTextIsSelectable(true)}
        col.addView(text(context,"Only on this phone. Nothing is sent automatically.",13f,C.muted))
        col.addView(preview)
        col.addStacked(button(context,"Save a copy",false) {host.saveDocument("HomeRoom-crash.txt",CrashLog.read(context),"text/plain")})
        col.addStacked(button(context,"Clear log",false) {context.deleteFile("last-crash.txt");preview.text=CrashLog.read(context)})
    }
}
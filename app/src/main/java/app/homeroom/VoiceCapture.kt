package app.homeroom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import java.io.File

/** Private bounded AAC recording. Explicit stop opens attachment review; never auto-sends. */
class VoiceCapture(context:Context,private val room:Room):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private val status=text(context,"Up to five minutes. Review before sending. Transcription may be unavailable.",15f,C.muted)
    private val stopButton=button(context,"Stop and review",false) {stop(true)}.apply {isEnabled=false;alpha=0.5f}
    private var recorder:android.media.MediaRecorder?=null
    private val startButton=button(context,"Start recording",true) {start()}
    private val idleStatus="Up to five minutes. Review before sending. Transcription may be unavailable."
    private fun showStop(on:Boolean) {stopButton.isEnabled=on;stopButton.alpha=if(on)1f else 0.5f;startButton.isEnabled=!on;startButton.alpha=if(on)0.5f else 1f}
    private val file=File(context.cacheDir,"voice-"+clientId()+".m4a")
    private var seconds=0
    private val timer=object:Runnable {override fun run() {if(recorder==null)return;seconds++;status.text="Recording: $seconds / 300 seconds";if(seconds>=300)stop(true) else postDelayed(this,1000)}}
    init {
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        status.textSize=13f;body.addCard(sectionCard(context).apply {addView(status)})
        body.addStacked(startButton)
        body.addStacked(stopButton)
        body.addStacked(button(context,"Cancel",false) {stop(false);file.delete();host.onBackPressedDispatcher.onBackPressed()})
    }
    @Suppress("DEPRECATION") private fun start() {
        if(recorder!=null)return
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(host,arrayOf(Manifest.permission.RECORD_AUDIO),705)
            status.text="Allow microphone access, then tap Start recording.";return
        }
        var created:android.media.MediaRecorder?=null
        try {
            val r=android.media.MediaRecorder()
            created=r
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000);r.setAudioSamplingRate(44100)
            r.setMaxDuration(300000);r.setMaxFileSize(10L*1024*1024)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener {_,what,_->if(what in listOf(android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED))stop(true)}
            r.prepare();r.start();recorder=r;showStop(true);seconds=0;postDelayed(timer,1000);status.text="Recording. Tap Stop and review when finished."
        }catch(_:Exception) {runCatching {created?.release()};recorder=null;showStop(false);file.delete();status.text="Couldn't start recording."}
    }
    private fun stop(review:Boolean) {
        val r=recorder ?: return
        recorder=null;removeCallbacks(timer);showStop(false);status.text=idleStatus
        try {r.stop();r.release();if(review && file.length()>0)host.openPage("Review voice note",AttachmentComposer(context,room,android.net.Uri.fromFile(file),"voice-note.m4a",true)) else file.delete()}
        catch(_:Exception) {r.release();file.delete();status.text="Recording wasn't saved. Try again."}
    }
    override fun onHide() {if(recorder!=null) {stop(false);status.text="Recording cancelled when you left this screen."};super.onHide()}
}
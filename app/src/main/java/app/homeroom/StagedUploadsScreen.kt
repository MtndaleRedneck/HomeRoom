package app.homeroom
import android.content.Context
import android.widget.ScrollView
import android.net.Uri
class StagedUploadsScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {
        body.removeAllViews();body.addView(text(context,"Unsent attachments",22f,C.text,true))
        body.addView(text(context,"Private copies let an interrupted upload resume. Server staging expires; a completed upload is not a sent message.",14f,C.muted))
        // Honest corruption state (I2): a damaged store shows no records but is
        // PRESERVED - all writes and removals are blocked until it is repaired;
        // signing out erases it only as an explicit owner choice.
        if(host.session.stagedUploadsMalformed()) {
            body.addView(text(context,"The local staged-upload records are damaged. To protect them, changes and removals are blocked. Signing out erases them.",14f,C.muted))
            return
        }
        host.session.stagedUploads().forEach {record->
            // Quarantined records (no usable identity) are kept verbatim and
            // shown honestly, but cannot be resumed.
            if(record.optBoolean("quarantined",false)) {
                body.addView(text(context,"Damaged record · cannot be resumed",15f,C.muted))
                // Manual removal is the only operation a quarantined record
                // supports; a blocked removal (damaged store) is said honestly.
                body.addView(button(context,"Remove damaged record",false) {
                    val ok=runCatching {host.session.saveStagedUpload(record.getString("local_id"),null)}.isSuccess
                    if(ok) onShow() else snack(this,"Local staged records are damaged - removal is blocked to protect them. Nothing was deleted.")
                })
                return@forEach
            }
            val room=host.cachedRooms().firstOrNull {it.id==record.optString("room")}
            body.addView(button(context,record.optString("name").ifEmpty {"Attachment"}+" · "+(room?.name ?: "Room unavailable"),false) {
                if(room==null) {snack(this,"Refresh the room list first.");return@button}
                host.openPage("Resume attachment",AttachmentComposer(context,room,Uri.EMPTY,record.optString("name"),record.optBoolean("voice"),record))
            })
        }
    }
}
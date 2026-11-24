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
        host.session.stagedUploads().forEach {record->
            val room=host.cachedRooms().firstOrNull {it.id==record.getString("room")}
            body.addView(button(context,record.getString("name")+" · "+(room?.name ?: "Room unavailable"),false) {
                if(room==null) {snack(this,"Refresh the room list first.");return@button}
                host.openPage("Resume attachment",AttachmentComposer(context,room,Uri.EMPTY,record.getString("name"),record.getBoolean("voice"),record))
            })
        }
    }
}
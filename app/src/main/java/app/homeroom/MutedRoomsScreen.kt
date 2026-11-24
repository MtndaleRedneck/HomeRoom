package app.homeroom

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch

class MutedRoomsScreen(context:Context):Screen(context) {
    private val body=column(context)
    private var loading=false
    init {body.setPadding(dp(16),dp(12),dp(16),dp(24));addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    private fun load() {
        if(loading)return
        loading=true
        host.scope.launch {
            try {
                val rooms=host.api.roomRoster().rooms.filter {it.muted}
                body.removeAllViews()
                if(rooms.isEmpty())body.addView(text(context,"No muted rooms.",15f,C.muted))
                rooms.forEach {room ->
                    val row=LinearLayout(context).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
                    row.addView(roomIcon(context,room,host.session.serverUrl,40),LinearLayout.LayoutParams(dp(40),dp(40)).apply {rightMargin=dp(12)})
                    row.addView(text(context,room.name,16f,C.text),LinearLayout.LayoutParams(0,-2,1f))
                    row.addView(text(context,"Unmute",14f,C.accent,true).apply {setPadding(dp(8),dp(16),dp(8),dp(16));setOnClickListener {
                        isEnabled=false
                        host.scope.launch {
                            try {host.api.muteRoom(room.id,false);loading=false;load()}
                            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
                            catch(_:Exception) {snack(this@MutedRoomsScreen,"Couldn't unmute this room. Try again.");isEnabled=true}
                        }
                    }})
                    body.addView(row,LinearLayout.LayoutParams(-1,dp(64)))
                }
            } catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {body.removeAllViews();body.addView(text(context,"Couldn't load muted rooms. Tap to retry.",15f,C.muted).apply {setOnClickListener {load()}})}
            finally {loading=false}
        }
    }
}
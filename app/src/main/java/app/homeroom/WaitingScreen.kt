package app.homeroom

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch

/** Reads every independent waiting cursor. Never infers notification delivery. */
class WaitingScreen(context:Context,private val onlyRoom:String?=null):Screen(context) {
    private val body=column(context)
    private val note=text(context,"Loading waiting messages",15f,C.muted)
    private var loading=false
    init {
        val scroll=ScrollView(context);scroll.addView(body);addView(scroll,LayoutParams(-1,-1))
        body.setPadding(dp(16),dp(12),dp(16),dp(24));body.addView(note)
    }
    override fun onShow() { load() }
    override fun poll() { load() }
    private fun load() {
        if(loading)return
        loading=true
        host.scope.launch {
            try {
                val roster=host.api.roomRoster()
                val result=mutableListOf<Pair<Room,WaitingEntry>>()
                for(room in roster.rooms.filter { onlyRoom==null || it.id==onlyRoom }) {
                    var cursor:String?=null
                    val seen=mutableSetOf<String>()
                    do {
                        val page=host.api.waitingPage(room.id,cursor)
                        result.addAll(page.objectList("waiting").map { room to WaitingEntry.from(it) })
                        cursor=if(page.optBoolean("has_more",false)) page.nullableString("cursor") else null
                        if(cursor!=null && !seen.add(cursor)) throw ApiException(0,"invalid_response_cursor")
                    } while(cursor!=null)
                }
                body.removeAllViews()
                if(result.isEmpty()) body.addView(text(context,"Nothing waiting.",15f,C.muted))
                result.sortedWith(compareBy<Pair<Room,WaitingEntry>> { it.second.since }.thenBy { it.second.eventId }.thenBy { it.second.waitingOn }).forEach { (room,entry) ->
                    val row=column(context).apply { minimumHeight=dp(64);setPadding(0,dp(8),0,dp(8));setOnClickListener { host.openRoomAt(room,entry.eventId) } }
                    val name=room.members.firstOrNull { it.first==entry.waitingOn }?.second ?: entry.waitingOn
                    row.addView(text(context,"Waiting on "+name+(serverAge(entry.since,roster.serverTime)?.let { " · "+it } ?: ""),15f,C.amber,true))
                    row.addView(text(context,room.name+" · "+entry.excerpt,13.5f,C.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
                    body.addView(row,LinearLayout.LayoutParams(-1,-2))
                }
            } catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) {
                note.text="Couldn't load waiting messages. Tap to retry."
                if(note.parent==null) body.addView(note,0)
                note.setOnClickListener { load() }
            } finally { loading=false }
        }
    }
}
// RECONSTRUCTED Nov 23, 2026: relative age for waiting rows. No matching helper
// was found in the exact release build; reconstruction-original, labeled gap.
private fun serverAge(since: String, serverTime: String?): String? {
    val start = runCatching { java.time.Instant.parse(since) }.getOrNull() ?: return null
    val end = runCatching { java.time.Instant.parse(serverTime ?: return null) }.getOrNull() ?: return null
    val mins = java.time.Duration.between(start, end).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> mins.toString() + "m"
        mins < 60 * 24 -> (mins / 60).toString() + "h"
        else -> (mins / (60 * 24)).toString() + "d"
    }
}

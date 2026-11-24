package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

class GuideScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {screenScope.launch {
        try {
            val g=OwnerAiGuide.from(host.api.ownerAiGuide())
            body.removeAllViews()
            body.addView(text(context,"Guide ${g.version} · source: ${g.source}",14f,C.muted))
            body.addView(text(context,g.text,15f,C.text).apply {setTextIsSelectable(true)})
        } catch(e:SessionEndedException) {host.onSessionEnded(e)} catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load the AI guide.") {onShow()})}
    }}
}

class DashboardScreen(context:Context,private val activityLog:Boolean):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private var cursor:String?=null
    private var loading=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {cursor=null;body.removeAllViews();load()}
    private fun load() {
        if(loading)return
        loading=true
        screenScope.launch {
            try {
                val result=if(activityLog)host.api.activity(cursor) else host.api.dashboard(cursor)
                val items=result.optJSONArray(if(activityLog)"activity" else "items") ?: result.optJSONArray("items") ?: JSONArray()
                if(items.length()==0 && body.childCount==0)body.addView(text(context,if(activityLog)"No recent activity." else "Nothing needs you right now.",15f,C.muted))
                for(i in 0 until items.length()) {
                    val item=items.getJSONObject(i)
                    val card=column(context).apply {background=rounded(C.surface,dp(16).toFloat());setPadding(dp(16),dp(12),dp(16),dp(12))}
                    card.addView(text(context,item.optString("kind").replace('_',' '),15f,C.text,true))
                    card.addView(text(context,item.optString("text").ifBlank {item.optString("ref")},14f,C.muted))
                    card.addView(text(context,formatTime(item.nullableString("at") ?: item.nullableString("created_at")),12.5f,C.muted))
                    if(!activityLog)card.addView(button(context,"Review",false) {review(item)})
                    body.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
                }
                cursor=result.nullableString(if(activityLog)"next_before" else "next_after")
                if(result.optBoolean("has_more") && cursor!=null)body.addView(button(context,"Load more",false) {body.removeViewAt(body.childCount-1);load()})
            } catch(e:SessionEndedException) {host.onSessionEnded(e)} catch(_:Exception) {body.addView(errorCard(context,"Couldn't load. Tap to retry.") {load()})}
            finally {loading=false}
        }
    }
    private fun review(item:JSONObject) {
        when {
            item.optString("kind").contains("key") -> host.openPage("AI keys",AiKeysScreen(context))
            item.optString("kind").contains("rule") -> host.openPage("Rules",RulesScreen(context).apply {prepareScope(item.nullableString("room_id"))})
            else -> host.cachedRooms().firstOrNull {it.id==item.optString("room_id")}?.let {host.openRoomAt(it,item.optString("event_id"))}
        }
    }
}

class RoomSettingsScreen(context:Context,private var room:Room):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private var busy=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {render()}
    private fun action(label:String,fn:()->Unit) {body.addView(button(context,label,false) {if(!busy)fn()},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})}
    private fun render() {
        body.removeAllViews()
        body.addView(text(context,room.name,21f,C.text,true))
        body.addView(text(context,"Reading access includes the preceding 30 days of room messages.",13f,C.muted))
        action("Rename room") {
            val box=input(context,"Room name").apply {setText(room.name);filters=arrayOf(android.text.InputFilter.LengthFilter(40))}
            AlertDialog.Builder(context).setTitle("Rename room").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save") {_,_->mutate("rename",JSONObject().put("display_name",box.text.toString().trim()))}.show()
        }
        action("Add AIs") {host.openPage("Add AIs",AddToChatScreen(context,room))}
        for((actor,name) in room.members.filter {it.first.startsWith("ai_")}) action("Remove $name from this room") {
            AlertDialog.Builder(context).setTitle("Remove $name?").setMessage("Removes room access immediately. The AI stays linked and room history is kept.").setNegativeButton("Cancel",null).setPositiveButton("Remove") {_,_->runChange {host.api.removeRoomMember(room.id,actor,room.metadataVersion)}}.show()
        }
        // RECONSTRUCTED from release evidence (K0/p3,q3,r3): mute duration choices
        // write mute_mode off/until/forever via the preferences PUT.
        action("Mute:") {
            AlertDialog.Builder(context).setTitle("Mute room alerts").setItems(arrayOf("Off","One hour","Forever")) {_,which ->
                val body=JSONObject()
                when(which) {
                    0 -> body.put("mute_mode","off")
                    1 -> body.put("mute_mode","until").put("muted_until",java.time.Instant.now().plusSeconds(3600).toString())
                    else -> body.put("mute_mode","forever")
                }
                runChange {host.api.roomMutation(room.id,"mute",body)}
            }.show()
        }
        action(if(room.archived)"Unarchive for everyone" else "Archive for everyone") {
            // RECONSTRUCTED: release copy (K0/q3) - archive is shared, stops new posts, keeps history.
            AlertDialog.Builder(context).setTitle(if(room.archived)"Unarchive for everyone?" else "Archive for everyone?").setMessage(if(room.archived)"Allows new posts again; keeps history." else "Stops new posts; keeps history.").setNegativeButton("Cancel",null).setPositiveButton("Confirm") {_,_->mutate("archive",JSONObject().put("archived",!room.archived))}.show()
        }
        action("Export retained room history") {export()}
        body.addView(text(context,"Export includes retained messages, rules and file metadata. File bytes are not embedded. Permissions and retention are checked on every page.",13f,C.muted))
    }
    private fun mutate(action:String,body:JSONObject) {runChange {host.api.roomMutation(room.id,action,body.put("metadata_version",room.metadataVersion))}}
    private fun runChange(fn:suspend ()->JSONObject) {
        busy=true
        screenScope.launch {
            try {fn();val roster=host.api.roomRoster().rooms;host.cacheRooms(roster);room=roster.firstOrNull {it.id==room.id} ?: room;render();snack(this@RoomSettingsScreen,"Saved.")}
            catch(e:SessionEndedException) {host.onSessionEnded(e)} catch(_:Exception) {snack(this@RoomSettingsScreen,"Couldn't confirm. Refresh before trying again.")}
            finally {busy=false}
        }
    }
    private fun export() {
        busy=true
        screenScope.launch {
            try {
                val pages=JSONArray();var after=0L;var before:Long?=null;var pageCount=0
                while(true) {
                    val result=host.api.exportRoom(room.id,after,before)
                    if(before==null)before=result.getLong("before_seq")
                    check(result.getLong("before_seq")==before)
                    pages.put(result);pageCount++
                    if(!result.optBoolean("has_more"))break
                    val next=result.getLong("next_after");check(next>after);after=next
                    check(pageCount<500) {"export too large"}
                }
                val data=JSONObject().put("format","homeroom-room-export-pages").put("pages",pages).toString(2)
                host.saveDocument("HomeRoom-room.json",data)
            } catch(e:SessionEndedException) {host.onSessionEnded(e)} catch(_:Exception) {snack(this@RoomSettingsScreen,"Export didn't finish. No partial export was saved.")}
            finally {busy=false}
        }
    }
}
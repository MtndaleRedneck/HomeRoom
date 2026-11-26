package app.homeroom

import android.content.Context
import android.app.AlertDialog
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject

class RoomPreferencesScreen(context:Context,private val room:Room):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    private var busy=false
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {load()}
    private fun load() {screenScope.launch {
        try {render(V15Api(host.api).get("rooms/"+ApiClient.enc(room.id)+"/preferences"))}
        catch(e:SessionEndedException) {host.onSessionEnded(e)}
        catch(_:Exception) {body.removeAllViews();body.addView(errorCard(context,"Couldn't load room preferences.") {load()})}
    }}
    private fun render(state:JSONObject) {
        body.removeAllViews();body.addView(text(context,room.name,22f,C.text,true))
        body.addStacked(button(context,if(state.getBoolean("pinned"))"Unpin room" else "Pin room",false) {change(state,state.getString("mute_mode"),state.nullableString("muted_until"),!state.getBoolean("pinned"),state.getBoolean("archived"))})
        body.addStacked(button(context,"Mute: "+state.getString("mute_mode"),false) {
            AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle("Mute room alerts").setItems(arrayOf("Off","One hour","Forever")) {_,which->
                val mode=listOf("off","until","forever")[which]
                val until=if(which==1)java.time.Instant.now().plusSeconds(3600).toString() else null
                change(state,mode,until,state.getBoolean("pinned"),state.getBoolean("archived"))
            }.show()
        })
        body.addView(text(context,"Archive is shared, not a private hide. It stops new posts for everyone and keeps history.",14f,C.amber))
        body.addStacked(button(context,if(state.getBoolean("archived"))"Unarchive for everyone" else "Archive for everyone",false) {
            AlertDialog.Builder(context,R.style.Theme_HomeRoom_Dialog).setTitle(if(state.getBoolean("archived"))"Unarchive for everyone?" else "Archive for everyone?")
                .setMessage(if(state.getBoolean("archived"))"Allows new posts again; keeps history." else "Stops new posts; keeps history.")
                .setNegativeButton("Cancel",null).setPositiveButton("Confirm") {_,_->change(state,state.getString("mute_mode"),state.nullableString("muted_until"),state.getBoolean("pinned"),!state.getBoolean("archived"))}.show()
        })
    }
    private fun change(state:JSONObject,mode:String,until:String?,pinned:Boolean,archived:Boolean) {
        if(busy)return;busy=true
        val body=JSONObject().put("client_id",clientId()).put("version",state.getInt("version")).put("pinned",pinned).put("mute_mode",mode).put("muted_until",until ?: JSONObject.NULL).put("archived",archived)
        if(archived!=state.getBoolean("archived"))body.put("metadata_version",state.getInt("metadata_version"))
        screenScope.launch {
            try {V15Api(host.api).mutate("rooms/"+ApiClient.enc(room.id)+"/preferences",body,"PUT");snack(this@RoomPreferencesScreen,"Preferences recorded.");load()}
            catch(e:SessionEndedException) {host.onSessionEnded(e)}
            catch(_:Exception) {snack(this@RoomPreferencesScreen,"Change not confirmed. Refresh before trying again.");load()}
            finally {busy=false}
        }
    }
}
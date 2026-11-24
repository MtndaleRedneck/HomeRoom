package app.homeroom

import android.content.Context
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

/** Immutable wire bodies are committed before any network send. */
internal class Outbox(private val session:Session) {
    fun entries():List<JSONObject> = session.outboxData()?.let {JSONArray(it).objects()} ?: emptyList()
    // Exact release (private12 K0/P2.d -> V4.s): every queue write runs inside
    // the session generation captured at outbox creation; a dead generation's
    // write throws CancellationException("Session changed"), never lands.
    private val generation=session.generation
    private fun save(items:List<JSONObject>) {session.withinGeneration(generation) {session.saveOutboxData(JSONArray(items).toString())}}
    fun enqueue(room:String,body:JSONObject):String=synchronized(lock) {
        require(body.getString("client_id").isNotBlank())
        val id=body.getString("client_id");val items=entries()
        val existing=items.firstOrNull {it.getString("id")==id}
        if(existing!=null) {require(existing.getJSONObject("body").toString()==body.toString());return@synchronized id}
        require(items.count {it.getString("state")!="sent"}<100)
        save(items+JSONObject().put("id",id).put("room",room).put("body",body).put("state","queued"));id
    }
    fun recover()=synchronized(lock) {save(entries().map {if(it.getString("state")=="sending")it.put("state","not_confirmed") else it})}
    fun retry(id:String)=synchronized(lock) {save(entries().map {if(it.getString("id")==id && it.getString("state")=="not_confirmed")it.put("state","queued") else it})}
    private fun state(id:String,value:String,error:String?=null)=synchronized(lock) {save(entries().map {if(it.getString("id")==id)it.put("state",value).put("error",error ?: JSONObject.NULL) else it})}
    suspend fun drain(api:ApiClient)=sendLock.withLock {
        for(entry in entries().filter {it.getString("state")=="queued"}) {
            val id=entry.getString("id");state(id,"sending")
            try {api.request("/rooms/"+ApiClient.enc(entry.getString("room"))+"/posts","POST",entry.getJSONObject("body"));state(id,"sent")}
            catch(e:CancellationException) {state(id,"not_confirmed");throw e}
            catch(e:SessionEndedException) {state(id,"not_confirmed");throw e}
            catch(e:ApiException) {state(id,if(e.status in 400..499 && e.status !in listOf(408,409,429))"rejected" else "not_confirmed",e.code)}
            catch(_:Exception) {state(id,"not_confirmed")}
        }
    }
    companion object {private val lock=Any();private val sendLock=Mutex()}
}
class OutboxScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {render()}
    override fun poll() {render()}
    private fun render() {
        body.removeAllViews();body.addView(text(context,"Outgoing messages",22f,C.text,true))
        body.addView(text(context,"Queued messages send when HomeRoom is open and connected. Not confirmed means the server may have recorded it. Retry sends the exact same message ID and body, never an edited copy.",14f,C.muted))
        val entries=Outbox(host.session).entries()
        if(entries.isEmpty())body.addView(text(context,"No outgoing messages.",15f,C.muted))
        entries.asReversed().forEach {entry->
            val room=host.cachedRooms().firstOrNull {it.id==entry.getString("room")}
            val state=entry.getString("state")
            body.addView(text(context,(room?.name ?: "Room")+" · "+state.replace('_',' '),16f,C.text,true))
            body.addView(text(context,entry.getJSONObject("body").optString("text").ifBlank {"Attachment"},15f,C.text))
            entry.nullableString("error")?.let {body.addView(text(context,it.replace('_',' '),14f,C.amber))}
            if(state=="not_confirmed")body.addView(button(context,"Retry exact same message",false) {Outbox(host.session).retry(entry.getString("id"));host.sendQueued();render()})
        }
        body.addView(button(context,"Send queued messages",true) {host.sendQueued();render()})
    }
}
package app.homeroom

import org.json.JSONObject
import java.util.UUID

/** Retry identity includes the complete wire body, including its original parent. */
internal data class OutgoingDraft(val id:String,val text:String,val replyId:String?,val thread:String?,val parent:String?) {
    fun json()=JSONObject().put("id",id).put("text",text).put("reply_id",replyId ?: JSONObject.NULL).put("thread",thread ?: JSONObject.NULL).put("parent",parent ?: JSONObject.NULL).toString()
    fun matches(text:String,reply:ChatEvent?)=this.text==text && (reply==null || replyId==reply.eventId)
    companion object {
        fun from(json:String):OutgoingDraft {
            val o=JSONObject(json)
            return OutgoingDraft(o.getString("id"),o.getString("text"),o.nullableString("reply_id"),o.nullableString("thread"),o.nullableString("parent"))
        }
        fun choose(saved:OutgoingDraft?,text:String,reply:ChatEvent?,thread:String?,events:List<ChatEvent>):OutgoingDraft =
            saved?.takeIf {it.matches(text,reply)} ?: OutgoingDraft(UUID.randomUUID().toString(),text,reply?.eventId,reply?.threadKey ?: thread,reply?.eventId ?: events.filter {it.threadKey==thread && !it.eventId.startsWith("local:")}.maxByOrNull {it.seq}?.eventId)
    }
}
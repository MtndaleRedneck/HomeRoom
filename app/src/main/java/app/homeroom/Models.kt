package app.homeroom

import org.json.JSONArray
import org.json.JSONObject

/** optString returns the string "null" for a JSON null value; this doesn't. */
private fun str(o: JSONObject, key: String): String =
    if (o.isNull(key)) "" else o.optString(key, "")

data class Room(
    val id: String,
    val name: String,
    val mainThreadKey: String?,
    val unreadCount: Int,
    val muted: Boolean,
    val waitingCount: Int,
    val pendingRulesCount: Int,
    val members: List<Pair<String, String>>,
    val icon: JSONObject? = null,
    val preview: String = "",
    val lastTime: String = "",
) {
    companion object {
        fun from(o: JSONObject): Room {
            val members = mutableListOf<Pair<String, String>>()
            val arr = o.optJSONArray("members") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                members.add(str(m, "actor") to str(m, "display_name"))
            }
            return Room(
                id = str(o, "room_id"),
                name = str(o, "display_name").ifBlank { str(o, "name") }.ifBlank { str(o, "room_id") },
                mainThreadKey = str(o, "main_thread_key").ifBlank { null },
                unreadCount = o.optInt("unread_count", 0),
                muted = o.optBoolean("muted", false),
                waitingCount = o.optInt("waiting_count", 0),
                pendingRulesCount = o.optInt("pending_rules_count", 0),
                members = members,
                icon = o.optJSONObject("icon"),
                preview = o.optJSONObject("last_post")?.let { str(it, "excerpt").ifBlank { str(it, "text") } } ?: "No retained messages",
                lastTime = o.optJSONObject("last_post")?.let { str(it, "created_at") } ?: "",
            )
        }
    }
}

data class ChatEvent(
    val eventId: String,
    val seq: Long,
    val threadKey: String?,
    val parentId: String?,
    val actorId: String,
    val actorName: String,
    val text: String,
    val createdAt: String,
    val clientId: String?,
) {
    companion object {
        fun from(o: JSONObject): ChatEvent {
            val actor = o.optJSONObject("actor")
            return ChatEvent(
                eventId = str(o, "event_id"),
                seq = o.optLong("seq", 0),
                threadKey = str(o, "thread_key").ifBlank { null },
                parentId = str(o, "parent_id").ifBlank { null },
                actorId = actor?.let { str(it, "actor") } ?: str(o, "actor"),
                actorName = actor?.let { str(it, "display_name") }?.ifBlank { null }
                    ?: actor?.let { str(it, "actor") } ?: str(o, "actor"),
                text = str(o, "text"),
                createdAt = str(o, "created_at").ifBlank { str(o, "ts") },
                clientId = str(o, "client_id").ifBlank { null },
            )
        }
    }
}

data class RoomFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uploader: String,
    val createdAt: String,
) {
    val isImage: Boolean
        get() = mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    companion object {
        fun from(o: JSONObject): RoomFile = RoomFile(
            id = str(o, "file_id"),
            name = str(o, "name").ifBlank { str(o, "file_id") },
            mimeType = str(o, "mime_type"),
            size = o.optLong("size", 0),
            uploader = str(o, "uploader"),
            createdAt = str(o, "created_at").ifBlank { str(o, "ts") },
        )
    }
}

data class Rule(
    val ruleId: String,
    val text: String,
    val version: Int,
    val proposedBy: String,
    val status: String,
    val roomId: String?,
) {
    companion object {
        fun from(o: JSONObject): Rule = Rule(
            ruleId = str(o, "rule_id"),
            text = str(o, "text"),
            version = o.optInt("version", 1),
            proposedBy = str(o, "proposed_by"),
            status = str(o,"status").ifBlank { "active" },
            roomId = str(o, "room_id").ifBlank { null },
        )
    }
}

data class SearchHit(
    val eventId: String,
    val roomId: String,
    val roomName: String,
    val excerpt: String,
    val createdAt: String,
) {
    companion object {
        fun from(o: JSONObject): SearchHit = SearchHit(
            eventId = str(o, "event_id"),
            roomId = str(o, "room_id"),
            roomName = str(o, "room_name").ifBlank { str(o, "room_id") },
            excerpt = str(o, "excerpt").ifBlank { str(o, "text") },
            createdAt = str(o, "created_at").ifBlank { str(o, "ts") },
        )
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.requiredArray(key: String): JSONArray {
    val array=optJSONArray(key) ?: throw ApiException(0,"invalid_response_"+key)
    for(i in 0 until array.length()) if(array.optJSONObject(i)==null) throw ApiException(0,"invalid_response_"+key)
    return array
}

fun JSONObject.nullableString(key:String):String? = if(isNull(key)) null else optString(key).ifBlank { null }

package app.homeroom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-room rolling store of the last few pushed messages, fed exclusively by
 * GET /owner/events/{id} fetches in NotificationFetchWorker - never by room
 * or history routes, so background work never extends the session idle
 * timer. It backs the MessagingStyle notification history.
 */
object PushMessageStore {

    private const val MAX_PER_ROOM = 5

    data class Entry(val eventId: String, val seq: Long, val sender: String, val text: String, val ts: Long)

    fun append(context: Context, roomId: String, entry: Entry): List<Entry> {
        val prefs = context.getSharedPreferences(HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        val key = "msgs_" + roomId
        val list = read(prefs.getString(key, null)).filter { it.eventId != entry.eventId }
        val updated = (list + entry).sortedBy { it.seq }.takeLast(MAX_PER_ROOM)
        val arr = JSONArray()
        updated.forEach { e ->
            arr.put(JSONObject()
                .put("event_id", e.eventId)
                .put("seq", e.seq)
                .put("sender", e.sender)
                .put("text", e.text)
                .put("ts", e.ts))
        }
        prefs.edit().putString(key, arr.toString()).apply()
        return updated
    }

    /** Drops a room's stored messages (read in the app or elsewhere) while
     *  keeping the seq watermark. */
    fun clearRoom(context: Context, roomId: String) {
        val prefs = context.getSharedPreferences(HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("msgs_" + roomId).apply()
    }

    /** Current stored entries for a room, oldest first. */
    fun entries(context: Context, roomId: String): List<Entry> {
        val prefs = context.getSharedPreferences(HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        return read(prefs.getString("msgs_" + roomId, null))
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("msgs_") || it.startsWith("last_seq_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun read(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    eventId = o.optString("event_id"),
                    seq = o.optLong("seq", 0),
                    sender = o.optString("sender"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

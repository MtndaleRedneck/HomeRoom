package app.homeroom

import org.json.JSONArray
import org.json.JSONObject

data class Room(
    val id: String,
    val name: String,
    val mainThreadKey: String?,
    val unreadCount: Int,
    val muted: Boolean,
    val waitingCount: Int,
    val pendingRulesCount: Int,
    val members: List<Pair<String, String>>,
    val waitingEntries: List<WaitingEntry> = emptyList(),
    val waitingHasMore: Boolean = false,
    val waitingCursor: String? = null,
    val lastPostExcerpt: String = "",
    val lastPostCreatedAt: String = "",
    val lastPostSeq: Long? = null,
    // RECONSTRUCTED: icon/metadata_version/archived/pinned recovered from the
    // exact private12 release build's room parser (k3 fields i/v/w + pin ops).
    val icon: JSONObject? = null,
    val metadataVersion: Int = 0,
    val archived: Boolean = false,
    val pinned: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject): Room {
            val members = mutableListOf<Pair<String, String>>()
            val arr = o.optJSONArray("members") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                members.add(m.optString("actor") to m.optString("display_name"))
            }
            // RECONSTRUCTED: the release resolves display_name first, then name,
            // then room_id, and drops the synthetic "homeroom-test" member.
            val displayName = o.optString("display_name")
            val resolvedName = (if (displayName.isNotBlank()) displayName else o.optString("name")).ifBlank { o.optString("room_id") }
            return Room(
                id = o.optString("room_id"),
                name = resolvedName,
                mainThreadKey = o.optString("main_thread_key").ifBlank { null },
                unreadCount = o.optInt("unread_count", 0),
                muted = o.optBoolean("muted", false),
                waitingCount = o.optInt("waiting_count", 0),
                pendingRulesCount = o.optInt("pending_rules_count", 0),
                members = members.filter { it.first != "homeroom-test" },
                waitingEntries = (o.optJSONArray("waiting")?.objects() ?: emptyList()).map { WaitingEntry.from(it) },
                waitingHasMore = o.optBoolean("waiting_has_more", false),
                waitingCursor = o.nullableString("waiting_cursor"),
                lastPostExcerpt = o.optJSONObject("last_post")?.let { lp -> lp.nullableString("excerpt") ?: lp.optString("text") } ?: "",
                lastPostCreatedAt = o.optJSONObject("last_post")?.optString("created_at") ?: "",
                lastPostSeq = o.optJSONObject("last_post")?.let { lp -> if (lp.has("seq")) lp.optLong("seq") else null },
                icon = o.optJSONObject("icon"),
                metadataVersion = o.optInt("metadata_version", 0),
                archived = o.optBoolean("archived", false),
                pinned = o.optBoolean("pinned", false),
            )
        }
    }
}

// RECONSTRUCTED Nov 23, 2026: waiting-entry model; JSON keys recovered from the
// exact private12 release build's roster parser.
data class WaitingEntry(
    val waitingOn: String,
    val eventId: String,
    val since: String,
    val notifiedAt: String?,
    val excerpt: String,
) {
    companion object {
        fun from(o: JSONObject): WaitingEntry = WaitingEntry(
            waitingOn = o.optString("waiting_on"),
            eventId = o.optString("event_id"),
            since = o.optString("since"),
            notifiedAt = o.nullableString("notified_at"),
            excerpt = o.optString("excerpt"),
        )
    }
}

// RECONSTRUCTED Nov 23, 2026: roster envelope (GET /owner/rooms); keys recovered
// from the exact private12 release build.
data class RoomRoster(
    val rooms: List<Room>,
    val serverTime: String?,
    val globalPendingRulesCount: Int,
    // RECONSTRUCTED: raw rooms array, cached for background sender-name mapping.
    val rawRooms: JSONArray,
) {
    companion object {
        fun from(o: JSONObject): RoomRoster = RoomRoster(
            rooms = o.requiredArray("rooms").objects().map { Room.from(it) },
            serverTime = o.nullableString("server_time"),
            globalPendingRulesCount = o.optInt("global_pending_rules_count", 0),
            rawRooms = o.requiredArray("rooms"),
        )
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
    // RECONSTRUCTED: events carry an edit/delete version (release model field,
    // optInt("version", 1) in the release parser) and a nullable room_id.
    val version: Int = 1,
    val roomId: String? = null,
) {
    companion object {
        fun from(o: JSONObject): ChatEvent {
            val actor = o.optJSONObject("actor")
            return ChatEvent(
                eventId = o.optString("event_id"),
                seq = o.optLong("seq", 0),
                threadKey = o.optString("thread_key").ifBlank { null },
                parentId = o.optString("parent_id").ifBlank { null },
                actorId = actor?.optString("actor") ?: o.optString("actor"),
                actorName = actor?.optString("display_name")?.ifBlank { null }
                    ?: actor?.optString("actor") ?: o.optString("actor"),
                text = o.optString("text"),
                version = o.optInt("version", 1),
                roomId = o.nullableString("room_id"),
                createdAt = o.optString("created_at").ifBlank { o.optString("ts") },
                clientId = o.optString("client_id").ifBlank { null },
            )
        }
    }
}

// RECONSTRUCTED Nov 23, 2026: kind/url/event_id fields and the byte_size key
// recovered from the exact private12 release build's file parser.
// RECONSTRUCTED: release file page wrapper (O1 in the release build).
data class RoomFilePage(val files: List<RoomFile>, val hasMore: Boolean, val cursor: String?)

data class RoomFile(
    val id: String,
    val name: String,
    // RECONSTRUCTED: title exists in the release RoomFile model (l3).
    val title: String? = null,
    val mimeType: String,
    val size: Long,
    val uploader: String,
    val createdAt: String,
    val kind: String,
    val url: String?,
    val eventId: String?,
) {
    val isImage: Boolean
        get() = mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    companion object {
        fun from(o: JSONObject): RoomFile = RoomFile(
            id = o.optString("file_id"),
            name = o.optString("name").ifBlank { o.optString("file_id") },
            title = o.nullableString("title"),
            mimeType = o.optString("mime_type"),
            size = o.optLong("byte_size", o.optLong("size", 0)),
            uploader = o.optString("uploader"),
            createdAt = o.optString("created_at").ifBlank { o.optString("ts") },
            kind = o.optString("kind").ifBlank { "file" },
            url = o.nullableString("url"),
            eventId = o.nullableString("event_id"),
        )
    }
}

// RECONSTRUCTED Nov 23, 2026: rules bundle, recovered from the exact private12
// release build (rules + pending_count + rereads map + ais; state filters
// active/proposed/sent_back).
// RECONSTRUCTED Nov 23, 2026: release guide model (S2 in the release build):
// version, sha256 (validated 64-hex when version>=1), source, updated_at, text,
// acks[{actor, version, at}].
data class GuideAck(val actor: String, val version: Int, val at: String?)

data class OwnerAiGuide(
    val version: Int,
    val sha256: String,
    val source: String,
    val updatedAt: String?,
    val text: String,
    val acks: List<GuideAck>,
) {
    companion object {
        fun from(o: JSONObject): OwnerAiGuide {
            val version = o.optInt("version", 0)
            val sha = o.nullableString("sha256") ?: ""
            if (version >= 1) require(Regex("[0-9a-fA-F]{64}").matches(sha)) { "invalid_response_guide" }
            val acks = o.optJSONArray("acks")?.objects()?.map { a ->
                val actor = a.nullableString("actor") ?: throw ApiException(0, "invalid_response_guide_ack")
                val av = a.optInt("version", 0)
                if (actor.isBlank() || av < 1) throw ApiException(0, "invalid_response_guide_ack")
                GuideAck(actor, av, a.nullableString("at"))
            } ?: emptyList()
            return OwnerAiGuide(version, sha, o.nullableString("source") ?: "", o.nullableString("updated_at"), o.nullableString("text") ?: "", acks)
        }
    }
}

data class RuleActor(val actor: String, val displayName: String, val lastCheckInAt: String?, val lastRulesReadAt: String?) {
    companion object { fun from(o: JSONObject) = RuleActor(o.optString("actor"), o.optString("display_name"), o.nullableString("last_check_in_at"), o.nullableString("last_rules_read_at")) }
}
data class RuleScope(val rules: List<Rule>, val pendingCount: Int, val rereads: Map<String, String>, val ais: List<RuleActor> = emptyList()) {
    val active get() = rules.filter { it.status == "active" }
    val pending get() = rules.filter { it.status == "proposed" }
    val sentBack get() = rules.filter { it.status == "sent_back" }
    val versions get() = rules
    companion object {
        fun from(o: JSONObject): RuleScope {
            val rr = o.optJSONObject("rereads")
            val map = rr?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap()
            val ais = o.optJSONArray("ais")?.objects()?.map { RuleActor.from(it) } ?: emptyList()
            return RuleScope(o.requiredArray("rules").objects().map { Rule.from(it) }, o.optInt("pending_count", 0), map, ais)
        }
    }
}
data class Rule(
    val ruleId: String,
    val text: String,
    val version: Int,
    val proposedBy: String,
    val status: String,
    val roomId: String?,
    // RECONSTRUCTED: decidedBy/decidedAt exist in the release Rule model (v4
    // toString); JSON key names are inferred snake_case (labeled inference).
    val decidedBy: String? = null,
    val decidedAt: String? = null,
) {
    companion object {
        fun from(o: JSONObject): Rule = Rule(
            ruleId = o.optString("rule_id"),
            text = o.optString("text"),
            version = o.optInt("version", 1),
            proposedBy = o.optString("proposed_by"),
            status = o.optString("status", "active"),
            roomId = o.optString("room_id").ifBlank { null },
            decidedBy = o.nullableString("decided_by"),
            decidedAt = o.nullableString("decided_at"),
        )
    }
}

data class SearchHit(
    val eventId: String,
    val roomId: String,
    val roomName: String,
    val excerpt: String,
    val createdAt: String,
    // RECONSTRUCTED: optional server-provided match range (-1 when absent).
    val matchStart: Int = -1,
    val matchLength: Int = 0,
) {
    companion object {
        fun from(o: JSONObject): SearchHit = SearchHit(
            eventId = o.optString("event_id"),
            roomId = o.optString("room_id"),
            roomName = o.optString("room_name").ifBlank { o.optString("room_id") },
            excerpt = o.optString("excerpt").ifBlank { o.optString("text") },
            createdAt = o.optString("created_at").ifBlank { o.optString("ts") },
            matchStart = o.optInt("match_start", -1),
            matchLength = o.optInt("match_length", 0),
        )
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.objectList(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()

// RECONSTRUCTED Nov 23, 2026: JSON helpers recovered from usage across the tree.
fun JSONObject.nullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.requiredArray(key: String): JSONArray =
    optJSONArray(key) ?: throw org.json.JSONException("missing array: " + key)
// RECONSTRUCTED Nov 23, 2026: not original source. Behavior recovered from
// the exact private12 release build (APK sha256
// f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d).
// Requires fresh review and tests before any release or equivalence claim.

data class AiKey(
    val keyId:String,
    val actor:String,
    val publicKeySha256:String,
    val status:String,
    val rooms:List<String>,
    val replaces:String?,
    val displayName:String?,
    val suspectPending:Boolean,
    val suffixLength:Int,
) {
    val name:String get() = displayName ?: actor
    /** Lowercased trailing characters of the fingerprint the owner must type to approve. */
    fun suffix():String = publicKeySha256.lowercase().takeLast(suffixLength.coerceIn(0, publicKeySha256.length))
    companion object {
        fun from(o:JSONObject):AiKey {
            val arr = o.optJSONArray("rooms")
            return AiKey(
                keyId = o.optString("key_id"),
                actor = o.optString("actor"),
                publicKeySha256 = o.optString("public_key_sha256"),
                status = o.optString("status"),
                rooms = arr?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                replaces = o.optString("replaces").takeIf { it.isNotEmpty() },
                displayName = o.optString("display_name").takeIf { it.isNotEmpty() },
                suspectPending = o.optBoolean("suspect_pending", false),
                suffixLength = o.optInt("fingerprint_suffix_length", 10).coerceIn(10, 64),
            )
        }
    }
}

// RECONSTRUCTED Nov 23, 2026: not original source. Field names and JSON keys
// recovered from the exact private12 release build's model parsers.

data class AiJoin(val joinId: String, val status: String, val expiresAt: String?) {
    companion object {
        fun from(o: JSONObject): AiJoin = AiJoin(o.optString("join_id"), o.optString("status"), o.nullableString("expires_at"))
    }
}

data class AiAvatar(val url: String, val sha256: String?) {
    companion object {
        fun from(o: JSONObject): AiAvatar? = o.nullableString("url")?.let { AiAvatar(it, o.nullableString("sha256")) }
    }
}

data class OwnerAi(
    val actor: String,
    val displayName: String,
    val kind: String,
    val keyStatus: String,
    val activeKeyId: String?,
    val join: AiJoin?,
    val avatar: AiAvatar?,
    val lastCheckInAt: String?,
    val lastRulesReadAt: String?,
    val mode: String?,
    val modeVersion: Int,
    val rooms: List<String>,
    val guide: JSONObject?,
    val lastApiError: JSONObject?,
    val pendingNoticeCount: Int,
) {
    companion object {
        fun from(o: JSONObject): OwnerAi = OwnerAi(
            actor = o.optString("actor"),
            displayName = o.optString("display_name"),
            kind = o.optString("kind"),
            keyStatus = o.optString("key_status"),
            activeKeyId = o.nullableString("active_key_id"),
            join = o.optJSONObject("join")?.let { AiJoin.from(it) },
            avatar = o.optJSONObject("avatar")?.let { AiAvatar.from(it) },
            lastCheckInAt = o.nullableString("last_check_in_at"),
            lastRulesReadAt = o.nullableString("last_rules_read_at"),
            mode = o.nullableString("mode"),
            modeVersion = o.optInt("mode_version", 0),
            rooms = o.optJSONArray("rooms")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } } } ?: emptyList(),
            guide = o.optJSONObject("guide"),
            lastApiError = o.optJSONObject("last_api_error"),
            pendingNoticeCount = o.optInt("pending_notice_count", 0),
        )
    }
}

data class JoinInvite(
    val joinId: String,
    val actor: String,
    val displayName: String,
    val expiresAt: String,
    val serverTime: String,
    val joinMessage: String,
) {
    /** Seconds until the invite expires, correcting for clock skew with the server time seen when the invite arrived. */
    fun secondsLeft(nowMillis: Long, receivedAtMillis: Long): Long {
        val expiry = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return 0
        val serverAtReceipt = runCatching { java.time.Instant.parse(serverTime).toEpochMilli() }.getOrNull() ?: receivedAtMillis
        val serverNow = serverAtReceipt + (nowMillis - receivedAtMillis)
        return ((expiry - serverNow) / 1000L).coerceAtLeast(0L)
    }

    companion object {
        fun from(o: JSONObject): JoinInvite = JoinInvite(
            joinId = o.optString("join_id"),
            actor = o.optString("actor"),
            displayName = o.optString("display_name"),
            expiresAt = o.optString("expires_at"),
            serverTime = o.optString("server_time"),
            joinMessage = o.optString("join_message"),
        )
    }
}

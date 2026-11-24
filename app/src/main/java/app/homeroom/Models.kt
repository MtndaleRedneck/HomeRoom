package app.homeroom

import org.json.JSONArray
import org.json.JSONObject

// RECONSTRUCTED-INFERRED Nov 24, 2026: the original Room.pinnedGuide type was
// not retained; fields follow the exact private12 release parser (m3/l):
// kind, document_path, version (optInt 0), sha256. The release only wraps the
// object when kind=ai_guide at the owner docs path.
data class PinnedGuide(val kind: String?, val documentPath: String?, val version: Int, val sha256: String?) {
    companion object {
        fun from(o: JSONObject): PinnedGuide = PinnedGuide(
            kind = o.nullableString("kind"),
            documentPath = o.nullableString("document_path"),
            version = o.optInt("version", 0),
            sha256 = o.nullableString("sha256"),
        )
    }
}

// Original-lineage helper object (actual historical source, Models.kt read
// Nov 11 11:03:01 EST): roster aggregates and ordering.
object RoomSummary {
    fun unreadRooms(rooms: List<Room>) = rooms.count { !it.muted && it.unreadCount > 0 }
    fun pendingRules(rooms: List<Room>, global: Int) = global + rooms.sumOf { it.pendingRulesCount }
    fun waiting(rooms: List<Room>) = rooms.sumOf { it.waitingCount }
    fun sorted(rooms: List<Room>) = rooms.sortedWith(compareByDescending<Room> { it.pinned }.thenByDescending { it.lastSeq ?: Long.MIN_VALUE })
}

// Field set, order and parser aligned to the actual original source
// (Models.kt read Nov 15 01:28:10 EST, via operator), which matches the
// private12 release ctor (k3) and parser (m3/l).
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
    val pinned: Boolean = false,
    val mutedUntil: String? = null,
    val firstUnreadSeq: Long? = null,
    val waiting: List<WaitingEntry> = emptyList(),
    val waitingHasMore: Boolean = false,
    val waitingCursor: String? = null,
    val lastActor: String? = null,
    val lastOwn: Boolean = false,
    val lastSeq: Long? = null,
    val pinnedGuide: PinnedGuide? = null,
    val metadataVersion: Int = 0,
    val archived: Boolean = false,
    val alertLevel: String = "all",
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
                icon = o.optJSONObject("icon"),
                preview = o.optJSONObject("last_post")?.let { lp -> lp.optString("excerpt").ifBlank { lp.optString("text") } } ?: "No retained messages",
                lastTime = o.optJSONObject("last_post")?.optString("created_at") ?: "",
                // Wire precedence per the owning historical parser (Models.kt
                // read Nov 15 01:28:10 EST, I2-reviewed): the preferences
                // OBJECT's presence gates the override - when it exists,
                // optBoolean("pinned") answers false even for a missing key
                // and top-level pinned is NOT consulted; only an absent
                // preferences object falls through to top-level pinned.
                pinned = o.optJSONObject("preferences")?.let { it.optBoolean("pinned") }
                    ?: o.optBoolean("pinned", false),
                mutedUntil = o.optJSONObject("preferences")?.nullableString("muted_until")
                    ?: o.nullableString("muted_until"),
                firstUnreadSeq = if (o.has("first_unread_seq") && !o.isNull("first_unread_seq")) o.optLong("first_unread_seq") else null,
                waiting = (o.optJSONArray("waiting")?.objects() ?: emptyList()).map { WaitingEntry.from(it) },
                waitingHasMore = o.optBoolean("waiting_has_more", false),
                waitingCursor = o.nullableString("waiting_cursor"),
                lastActor = o.optJSONObject("last_post")?.nullableString("actor"),
                lastOwn = o.optJSONObject("last_post")?.optBoolean("own", false) ?: false,
                lastSeq = o.optJSONObject("last_post")?.let { lp -> if (lp.has("seq") && !lp.isNull("seq")) lp.optLong("seq") else null },
                pinnedGuide = o.optJSONObject("pinned_guide")?.let { PinnedGuide.from(it) },
                metadataVersion = o.optInt("metadata_version", 0),
                archived = o.optBoolean("archived", false),
                alertLevel = o.optString("alert_level", "all"),
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

// RECONSTRUCTED Nov 24, 2026: assistant status entry (V0 in the release
// build), carried on ChatEvent.assistant_statuses: actor, reply_event_id,
// notified_at.
data class AssistantStatus(val actor: String, val replyEventId: String?, val notifiedAt: String?) {
    // Actual original source (Models.kt read Nov 11 08:20:50 EST, verified by
    // operator against the earlier inference): blank strings count as absent.
    val replied: Boolean get() = !replyEventId.isNullOrBlank()
    val notified: Boolean get() = !notifiedAt.isNullOrBlank()
    companion object {
        fun from(o: JSONObject) = AssistantStatus(o.optString("actor"), o.nullableString("reply_event_id"), o.nullableString("notified_at"))
    }
}

// Original-lineage reaction summary (actual historical source names it
// ReactionCount; j3 in the release build): reaction (required), count
// (required), mine (optBoolean, default false). Single home: V15Models.kt's
// earlier copy was removed after CI flagged the redeclaration.
data class ReactionCount(val reaction: String, val count: Int, val mine: Boolean) {
    companion object {
        fun from(o: JSONObject): ReactionCount =
            ReactionCount(o.getString("reaction"), o.getInt("count"), o.optBoolean("mine", false))
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
    // Remaining fields aligned to the actual original source (Models.kt read
    // Nov 15 01:28:10 EST, via operator), matching the private12 release ctor
    // (s1 -> t1, 21 params) and parser.
    val own: Boolean = false,
    val mentions: List<String> = emptyList(),
    val assistantStatuses: List<AssistantStatus> = emptyList(),
    val kind: String? = null,
    val roomId: String? = null,
    val attachments: List<RoomFile> = emptyList(),
    val version: Int = 1,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val decisionId: String? = null,
    val requestId: String? = null,
    val reactions: List<ReactionCount> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject): ChatEvent {
            val actor = o.optJSONObject("actor")
            // Release semantics (s1): a deleted event renders as
            // "Message deleted" and carries no attachments. Parser throws on a
            // malformed mentions array; the release's page-level caller
            // catches Exception and drops the page update, never crashes
            // (verified in the private12 decompile, e4).
            val deleted = o.optBoolean("deleted", false)
            val mentions = if (!o.isNull("mentions") && o.has("mentions")) {
                val arr = o.optJSONArray("mentions")
                    ?: throw IllegalArgumentException("invalid_response_mentions")
                (0 until arr.length()).map { i ->
                    arr.opt(i) as? String
                        ?: throw IllegalArgumentException("invalid_response_mentions")
                }
            } else emptyList()
            return ChatEvent(
                eventId = o.optString("event_id"),
                seq = o.optLong("seq", 0),
                threadKey = o.optString("thread_key").ifBlank { null },
                parentId = o.optString("parent_id").ifBlank { null },
                actorId = actor?.optString("actor") ?: o.optString("actor"),
                actorName = actor?.optString("display_name")?.ifBlank { null }
                    ?: actor?.optString("actor") ?: o.optString("actor"),
                text = if (deleted) "Message deleted" else o.optString("text"),
                version = o.optInt("version", 1),
                roomId = o.nullableString("room_id"),
                createdAt = o.optString("created_at").ifBlank { o.optString("ts") },
                clientId = o.optString("client_id").ifBlank { null },
                own = o.optBoolean("own", false),
                mentions = mentions,
                assistantStatuses = (o.optJSONArray("assistant_statuses")?.objects() ?: emptyList()).map { AssistantStatus.from(it) },
                kind = o.nullableString("kind"),
                attachments = if (deleted) emptyList() else
                    (o.optJSONArray("attachments")?.objects() ?: emptyList()).map { RoomFile.from(it) },
                editedAt = o.nullableString("edited_at"),
                deleted = deleted,
                decisionId = o.nullableString("decision_id"),
                requestId = o.nullableString("request_id"),
                reactions = (o.optJSONArray("reactions")?.objects() ?: emptyList()).map { ReactionCount.from(it) },
            )
        }
    }
}

// RECONSTRUCTED Nov 23, 2026: kind/url/event_id fields and the byte_size key
// recovered from the exact private12 release build's file parser.
// RECONSTRUCTED: release file page wrapper (O1 in the release build).
data class RoomFilePage(val files: List<RoomFile>, val hasMore: Boolean, val cursor: String?)

// Field order/defaults aligned to the actual original source (Models.kt read
// Nov 15 01:28:10 EST, via operator), matching the private12 release ctor
// (l3) and file parser (n1/a.z).
data class RoomFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uploader: String,
    val createdAt: String,
    val kind: String = "file",
    val url: String? = null,
    val title: String? = null,
    val eventId: String? = null,
    val sha256: String? = null,
    val digestState: String = "unverified",
    val transcript: JSONObject? = null,
) {
    val isImage: Boolean
        get() = mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    companion object {
        fun from(o: JSONObject): RoomFile = RoomFile(
            id = o.optString("file_id"),
            name = o.optString("name").ifBlank { o.optString("file_id") },
            mimeType = o.optString("mime_type"),
            size = o.optLong("byte_size", 0),
            uploader = o.optString("uploader"),
            createdAt = o.optString("created_at").ifBlank { o.optString("ts") },
            kind = o.optString("kind").ifBlank { "file" },
            url = o.nullableString("url"),
            title = o.nullableString("title"),
            eventId = o.nullableString("event_id"),
            // Original source: sha256 runs through UpdateCheck.parseDigest
            // (sha256: prefix stripped, must be 64 hex chars, else null).
            sha256 = UpdateCheck.parseDigest(o.nullableString("sha256")),
            digestState = o.optString("digest_state", "unverified"),
            transcript = o.optJSONObject("transcript"),
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
    /** Original source (Models.kt read Nov 15 01:28:31 EST): the characters the
        owner types to confirm: the end of the full fingerprint, lowercase. */
    val confirmationSuffix:String get()=publicKeySha256.lowercase().takeLast(suffixLength)
    fun confirmationMatches(typed:String):Boolean=typed.trim().lowercase().let {it.length==suffixLength && it==confirmationSuffix}
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

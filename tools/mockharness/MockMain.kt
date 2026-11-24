package app.homeroom

import okhttp3.HttpUrl.Companion.toHttpUrl

import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Runtime mock-HTTP contract harness (NOT part of the app build).
 * Points the real reconstructed ApiClient at a local fake server
 * (tools/mockharness/mock_server.py) and exercises the golden routes.
 * The fake server asserts method/path/body keys per tools/contract_golden.json
 * and returns full-shape responses whose distinctive sentinel values are
 * asserted here, proving each parsed field survived the client's parsing
 * (optString-based parsers silently default missing fields, so a bare
 * "no exception" pass would not prove the shape was read).
 */
fun main(args: Array<String>) = runBlocking {
    val base = args.getOrNull(0) ?: "http://127.0.0.1:8471"
    val session = Session().apply { serverUrl = base }
    val api = ApiClient(session)
    val results = mutableListOf<String>()
    fun check(name: String, block: suspend () -> Any?) {
        try { block.let { kotlinx.coroutines.runBlocking { it() } }; results.add("OK   $name") }
        catch (t: Throwable) { results.add("FAIL $name: ${t.message}") }
    }
    check("profile-get") {
        val (name, avatar) = api.ownerProfile()
        require(name == "Owner") { "display_name sentinel lost: $name" }
        require(avatar?.url == "https://example.invalid/a.png") { "avatar sentinel lost" }
    }
    check("cookie-origin-scoping") {
        // The session cookie goes only to the enrolled origin: scheme, host
        // AND effective port must match. Foreign, suffix-trick, other-port
        // and other-scheme endpoints never receive it.
        require(api.sessionCookieFor("http://127.0.0.1:8471/x".toHttpUrl()) == "test-cookie") { "same-origin cookie lost" }
        require(api.sessionCookieFor("http://127.0.0.1:9999/x".toHttpUrl()) == null) { "other-port endpoint received session cookie" }
        require(api.sessionCookieFor("https://127.0.0.1:8471/x".toHttpUrl()) == null) { "other-scheme endpoint received session cookie" }
        require(api.sessionCookieFor("http://example.invalid/x".toHttpUrl()) == null) { "foreign host received session cookie" }
        require(api.sessionCookieFor("http://127.0.0.1.evil.example/x".toHttpUrl()) == null) { "suffix-trick host received session cookie" }
    }
    check("avatar-https-only") {
        // Cleartext absolute avatar URLs are refused before any network call.
        val e = try { api.avatarBytes("http://example.invalid/a.png"); null } catch (x: ApiException) { x }
        require(e?.code == "avatar_url_not_https") { "cleartext absolute avatar URL accepted" }
    }
    check("staged-upload-identity") {
        // Interrupted-upload restart keeps the ORIGINAL identities: a retried
        // create/complete/post carries the same idempotency keys.
        var n = 0
        val ids = StagedUpload.idsFor(null, { "fresh-" + (++n) }, { "t0" })
        require(ids.uploadClient == "fresh-1" && ids.completeClient == "fresh-2" && ids.postClient == "fresh-3" && ids.created == "t0") { "fresh identity minting wrong: $ids" }
        val rec = StagedUpload.newRecord("u1", "room1", "a.txt", false, 12, "abc", "attachment-x", 0, ids)
        val restored = StagedUpload.idsFor(rec, { "WRONG" }, { "WRONG" })
        require(restored == ids) { "resume minted new identities" }
        require(StagedUpload.resumable(rec)) { "fresh record not resumable" }
        val legacy = org.json.JSONObject().put("upload", "u1").put("size", 12).put("file", "attachment-x")
        require(!StagedUpload.resumable(legacy)) { "legacy record (no hash/IDs) accepted as resumable" }
    }
    check("staged-upload-complete-gate") {
        // The gate every resume must pass before Send enables; mirrors the
        // fresh path. Accepts only a fully verified ready completion.
        StagedUpload.requireComplete("abc", 12, "abc", "ready", "abc", 12)
        val e1 = try { StagedUpload.requireComplete("abc", 12, "abc", "processing", null, 12); null } catch (x: IllegalArgumentException) { x }
        require(e1 != null) { "gate accepted unfinished upload" }
        val e2 = try { StagedUpload.requireComplete("abc", 12, "abc", "ready", "WRONGHASH", 12); null } catch (x: IllegalArgumentException) { x }
        require(e2 != null) { "gate accepted wrong verified hash" }
        val e3 = try { StagedUpload.requireComplete("abc", 12, "abc", "ready", "abc", 11); null } catch (x: IllegalArgumentException) { x }
        require(e3 != null) { "gate accepted wrong size" }
        val e4 = try { StagedUpload.requireComplete("abc", 12, "CHANGED", "ready", "abc", 12); null } catch (x: IllegalArgumentException) { x }
        require(e4 != null) { "gate accepted changed staged file" }
    }
    check("profile-set-name") { require(api.setOwnerName("Owner") == "Owner") { "set-name echo lost" } }
    check("ai-guide") {
        val o = api.ownerAiGuide()
        require(o.getJSONArray("acks").getJSONObject(0).getString("actor") == "ai_1") { "ack sentinel lost" }
        require(o.getString("sha256").length == 64) { "sha256 sentinel lost" }
    }
    check("rooms-roster") {
        val r = api.roomRoster()
        require(r.rooms.size == 1) { "rooms count ${r.rooms.size}" }
        val room = r.rooms[0]
        require(room.id == "room1") { "room_id sentinel lost" }
        require(room.name == "The Den") { "display_name resolution wrong: ${room.name}" }
        require(room.mainThreadKey == "t1") { "main_thread_key lost" }
        require(room.unreadCount == 2 && room.waitingCount == 1 && room.pendingRulesCount == 1) { "counts lost" }
        // the synthetic homeroom-test member must be filtered out by the release behavior
        require(room.members.size == 1 && room.members[0].first == "ai_1" && room.members[0].second == "Helper") {
            "member filter wrong: ${room.members}" }
        require(room.waitingEntries.size == 1 && room.waitingEntries[0].eventId == "ev9" &&
            room.waitingEntries[0].waitingOn == "ai_1") { "waiting entry lost" }
        require(room.lastPostExcerpt == "last words" && room.lastPostSeq == 41L) { "last_post lost" }
        require(room.icon?.optString("emoji") == "den") { "icon lost" }
        require(room.metadataVersion == 3 && room.pinned && !room.archived) { "metadata flags lost" }
        require(r.globalPendingRulesCount == 1) { "global pending count lost" }
    }
    check("activity") { require(api.activity(null).getJSONArray("items").length() == 1) { "activity items lost" } }
    check("needs-you") { require(api.dashboard(null).getJSONArray("items").length() == 1) { "needs-you items lost" } }
    check("search") {
        val hits = api.searchPage("goal", null, null).getJSONArray("results")
        require(hits.length() == 1) { "search results lost" }
        val h = hits.getJSONObject(0)
        require(h.getString("excerpt") == "goal met" && h.getInt("match_start") == 3) { "search hit fields lost" }
    }
    check("files-page") {
        val p = api.filePage("room1", null)
        require(p.files.size == 1) { "files lost" }
        val f = p.files[0]
        require(f.id == "f1" && f.name == "notes.txt" && f.title == "Notes") { "file identity lost" }
        require(f.size == 12L && f.uploader == "ai_1" && f.kind == "file") { "file fields lost" }
        require(f.url == "https://example.invalid/f1" && f.eventId == "ev1") { "file url/event lost" }
        require(p.hasMore && p.cursor == "fc1") { "page cursor lost" }
    }
    check("held-updates") {
        val o = api.heldUpdates(null, 0)
        require(o.getJSONArray("changes").length() == 1 && o.getString("next_cursor") == "c1") { "held-updates lost" }
    }
    check("event-get") {
        val e = api.event("ev1").getJSONObject("event")
        require(e.getString("event_id") == "ev1" && e.getInt("version") == 2) { "event fields lost" }
        require(e.getJSONObject("actor").getString("display_name") == "Helper") { "event actor lost" }
    }
    check("read-events") { api.markReadEvents("t1", listOf("ev1")) }
    check("join-code-create") {
        val j = api.createJoinCode("ai_1", listOf("room1"))
        require(j.joinId == "j1" && j.actor == "ai_1" && j.displayName == "AI") { "invite identity lost" }
        require(j.expiresAt.startsWith("2026-11-24") && j.serverTime.startsWith("2026-11-23")) { "invite times lost" }
        require(j.secondsLeft(System.currentTimeMillis(), System.currentTimeMillis()) > 0) { "expiry math broken" }
    }
    check("join-code-revoke") { api.revokeJoinCode("ai_1", "j1") }
    check("join-code-report-used") { api.reportJoinCodeUsed("ai_1", "j1") }
    // The release gates rule creation behind a server capability; the harness
    // has no features, so capability_required is the CORRECT outcome here.
    check("rule-create-gate") {
        try { api.proposeRule(null, "be kind", "c1"); throw IllegalStateException("gate_missing") }
        catch (e: IllegalArgumentException) { require(e.message == "capability_required") { "wrong failure: ${e.message}" } }
    }
    check("feedback") { api.sendFeedback("hello", "c2") }
    check("push-register") { api.registerPushToken("tok", "HomeRoom Android app", "1.5") }
    check("session-renew") { api.renewOnOpen() }
    check("room-preferences-get") {
        require(api.roomPreferences("room1").getString("mute_mode") == "off") { "preferences lost" }
    }
    results.forEach { println(it) }
    if (results.any { it.startsWith("FAIL") }) kotlin.system.exitProcess(1)
}

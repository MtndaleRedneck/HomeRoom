package app.homeroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SessionEndedException(val reason: String?) : Exception("session_ended")

class ApiException(val status: Int, val code: String, val retryAfter: Int = 0) : Exception(code)

/**
 * Speaks to the HomeRoom server API under /homeroom/v1.
 *
 * Auth is the __Host-homeroom session cookie. Non-GET requests carry the
 * CSRF token from the latest /owner/session bootstrap. A 409 session_rotated
 * or 403 csrf_failed is retried once after a fresh bootstrap; a 401 ends the
 * session and surfaces as SessionEndedException.
 */
class ApiClient(private val session: Session) {
    // RECONSTRUCTED Nov 23, 2026: request-health surface for the status screen.
    var reachable: Boolean = true
        private set
    var lastSuccessfulRefresh: String? = null
        private set

    @Volatile
    private var csrf: String? = session.csrf

    @Volatile
    var sessionData: JSONObject? = null
        private set

    private val bootMutex = Mutex()

    // RECONSTRUCTION-ORIGINAL: cap on avatar downloads (absolute-URL path
    // included). The release's cap, if any, is unknown; 8 MiB is far above
    // any legitimate avatar.
    private val AVATAR_MAX_BYTES = 8 * 1024 * 1024

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.firstOrNull { it.name == COOKIE_NAME }?.let { session.cookie = it.value }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val value = cookieValueForHost(url.host) ?: return emptyList()
                return listOf(
                    Cookie.Builder()
                        .name(COOKIE_NAME)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .secure()
                        .build()
                )
            }
        })
        .build()

    // RECONSTRUCTION-ORIGINAL hardening (not release-evidenced): the session
    // cookie only ever goes to the enrolled server's host. Server-supplied
    // absolute URLs (e.g. avatar URLs) could otherwise carry the session to a
    // foreign host. Internal so the mock harness can pin the boundary.
    internal fun cookieValueForHost(host: String): String? {
        val value = session.cookie ?: return null
        return if (host == serverHost()) value else null
    }

    private fun serverHost(): String? = try {
        session.serverUrl?.let { java.net.URI(it).host?.lowercase() }
    } catch (e: Exception) { null }

    private fun baseUrl(): String =
        (session.serverUrl ?: throw ApiException(0, "no_server")).trimEnd('/') + API_PREFIX

    private fun raw(route: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl() + route)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, body.toString().toRequestBody(JSON))
        } else if (method != "GET") {
            builder.method(method, "{}".toRequestBody(JSON))
        } else {
            builder.get()
        }
        if (method != "GET") csrf?.let { builder.header("X-CSRF-Token", it) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val data = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(response.code, "invalid_response")
            }
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 0
                throw ApiException(response.code, data.optString("error", "request_failed"), retryAfter)
            }
            return data
        }
    }

    /** Streams an owner file through the app's own client. The cookie never leaves the app. Returns the MIME type. */
    suspend fun downloadOwnerFile(id:String, out:java.io.OutputStream): String = withContext(Dispatchers.IO) {
        val builder=Request.Builder().url(baseUrl()+"/owner/files/"+enc(id)).header("Cache-Control","no-store").get()
        applyOriginHeaders(builder,baseUrl(),"GET")
        client.newCall(builder.build()).execute().use { response ->
            checkSession()
            if(!response.isSuccessful) {
                if(response.code==401) throw SessionEndedException("owner_session_required")
                throw ApiException(response.code,"download_failed")
            }
            response.body?.byteStream()?.use {it.copyTo(out)} ?: throw ApiException(response.code,"download_failed")
            response.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
        }
    }

    suspend fun bootstrap(): JSONObject = bootMutex.withLock {
        withContext(Dispatchers.IO) {
            var data = try {
                raw("/owner/session")
            } catch (e: ApiException) {
                if (e.status == 409 && e.code == "session_rotated") raw("/owner/session") else throw e
            }
            csrf = data.optString("csrf").ifBlank { null }
            session.csrf = csrf
            sessionData = data
            session.ownerId = data.optString("owner_id").ifBlank { session.ownerId }
            session.ownerName = data.optString("owner_display_name").ifBlank { session.ownerName }
            session.sessionId = data.optString("session_id").ifBlank { session.sessionId }
            data
        }
    }

    suspend fun request(route: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (sessionData == null) bootstrap()
            try {
                raw(route, method, body).also {
                    reachable = true
                    lastSuccessfulRefresh = java.time.Instant.now().toString()
                }
            } catch (e: ApiException) {
                if ((e.status == 409 && e.code == "session_rotated") ||
                    (e.status == 403 && e.code == "csrf_failed")
                ) {
                    sessionData = null
                    bootstrap()
                    try {
                        raw(route, method, body).also {
                            reachable = true
                            lastSuccessfulRefresh = java.time.Instant.now().toString()
                        }
                    } catch (again: ApiException) {
                        reachable = false
                        if (again.status == 401) throw SessionEndedException(again.code)
                        throw again
                    }
                } else {
                    reachable = false
                    if (e.status == 401) throw SessionEndedException(e.code)
                    throw e
                }
            } catch (e: java.io.IOException) {
                reachable = false
                throw e
            }
        }

    // RECONSTRUCTED Nov 23, 2026: the release renews the owner session when the
    // app opens (session_renew evidence from the exact release build).
    // RECONSTRUCTED Nov 23, 2026: more exact release routes (K0/J0, E0):
    // GET /owner/profile -> {display_name, avatar?}; join codes, AI avatar/unlink,
    // chunk PUT, search, files page, held updates, push register.
    suspend fun ownerProfile(): Pair<String, AiAvatar?> {
        val o = request("/owner/profile")
        return o.optString("display_name") to (o.optJSONObject("avatar")?.let { AiAvatar.from(it) })
    }

    suspend fun createJoinCode(actor: String, rooms: List<String>): JoinInvite =
        JoinInvite.from(request("/owner/ais/" + enc(actor) + "/join-codes", "POST",
            JSONObject().put("rooms", JSONArray(rooms))))

    suspend fun revokeJoinCode(actor: String, joinId: String): JSONObject =
        request("/owner/ais/" + enc(actor) + "/join-codes/" + enc(joinId) + "/revoke", "POST", JSONObject())

    suspend fun setAiAvatar(actor: String, upload: AvatarUpload?): AiAvatar? =
        request("/owner/ais/" + enc(actor) + "/avatar", "POST", JSONObject().put("image",
            if (upload == null) JSONObject.NULL
            else JSONObject().put("mime_type", "image/jpeg").put("base64", upload.base64)
        )).optJSONObject("avatar")?.let { AiAvatar.from(it) }

    suspend fun unlinkAi(actor: String): JSONObject =
        request("/owner/ais/" + enc(actor) + "/unlink", "POST", JSONObject())

    // RECONSTRUCTED: exact release chunk route (K0/E0) - PUT octet-stream with
    // X-Chunk-SHA256 and X-CSRF-Token.
    suspend fun uploadChunk(uploadId: String, offset: Long, chunk: ByteArray, sha256Hex: String): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl() + "/owner/uploads/" + enc(uploadId) + "/chunks/" + offset)
                .header("X-Chunk-SHA256", sha256Hex)
                .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
            csrf?.let { builder.header("X-CSRF-Token", it) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) throw SessionEndedException("owner_session_required")
                    throw ApiException(response.code, "upload_failed")
                }
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            }
        }

    suspend fun postWithUploads(room: String, clientId: String, created: String?, text: String,
                                threadKey: String?, parentId: String?, mentions: List<String>, uploadIds: List<String>): JSONObject =
        request("/rooms/" + enc(room) + "/posts", "POST", JSONObject()
            .put("client_id", clientId)
            .put("client_created_at", created ?: JSONObject.NULL)
            .put("text", text)
            .put("thread_key", threadKey ?: JSONObject.NULL)
            .put("parent_id", parentId ?: JSONObject.NULL)
            .put("upload_ids", JSONArray(uploadIds)))

    // RECONSTRUCTED: avatar bytes through the app's own client (cookie stays in-app).
    suspend fun avatarBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        // RECONSTRUCTION-ORIGINAL hardening (release behavior unverified):
        // absolute avatar URLs must be https, and downloads are size-capped.
        // The cookie jar above additionally scopes the session cookie to the
        // enrolled server, so a foreign host receives no credentials.
        val full = if (url.startsWith("http")) {
            if (!url.startsWith("https://")) throw ApiException(0, "avatar_url_not_https")
            url
        } else baseUrl() + url
        client.newCall(Request.Builder().url(full).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "download_failed")
            val stream = response.body?.byteStream() ?: throw ApiException(response.code, "download_failed")
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AVATAR_MAX_BYTES) throw ApiException(0, "avatar_too_large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }
    }

    suspend fun searchPage(query: String, room: String?, cursor: String?): JSONObject =
        request("/owner/search?q=" + enc(query) + "&limit=50" +
            (room?.let { "&room=" + enc(it) } ?: "") +
            (cursor?.let { "&cursor=" + enc(it) } ?: ""))

    suspend fun filePage(room: String, cursor: String?, limit: Int = 50): RoomFilePage {
        val o = request("/owner/rooms/" + enc(room) + "/files?limit=" + limit +
            (cursor?.let { "&cursor=" + enc(it) } ?: ""))
        return RoomFilePage(o.requiredArray("files").objects().map { RoomFile.from(it) },
            o.optBoolean("has_more", false), o.nullableString("cursor"))
    }

    // RECONSTRUCTED: exact release route - GET /owner/updates?wait=<0..25>&limit=100(&cursor=).
    suspend fun heldUpdates(cursor: String?, waitSeconds: Int): JSONObject =
        request("/owner/updates?wait=" + waitSeconds.coerceIn(0, 25) + "&limit=100" +
            (cursor?.let { "&cursor=" + enc(it) } ?: ""))

    // RECONSTRUCTED: exact release body (K0/J0:494).
    suspend fun registerPushToken(token: String, deviceLabel: String, appVersion: String): JSONObject =
        request("/push/register", "POST", JSONObject()
            .put("token", token)
            .put("platform", "fcm")
            .put("device_label", deviceLabel)
            .put("app_version", appVersion))

    // RECONSTRUCTED Nov 23, 2026: exact release routes (K0/J0):
    // GET /owner/docs/ai-guide, GET /owner/rooms/<id>/export?format=json&after=&limit=200(&before_seq=).
    suspend fun ownerAiGuide(): JSONObject = request("/owner/docs/ai-guide")

    suspend fun exportRoom(room: String, after: Long, beforeSeq: Long?): JSONObject =
        request("/owner/rooms/" + enc(room) + "/export?format=json&after=" + after + "&limit=200" +
            (beforeSeq?.let { "&before_seq=" + it } ?: ""))

    // RECONSTRUCTED Nov 23, 2026: owner-tools endpoints recovered from the exact
    // private12 release build (paths, cursor params and bodies).
    suspend fun activity(cursor: String?): JSONObject =
        request("/owner/activity?limit=50" + (cursor?.let { "&before=" + enc(it) } ?: ""))

    suspend fun dashboard(cursor: String?): JSONObject =
        request("/owner/needs-you?limit=50" + (cursor?.let { "&after=" + enc(it) } ?: ""))

    suspend fun setOwnerName(name: String): String =
        request("/owner/profile", "POST", JSONObject().put("display_name", name)).getString("display_name")

    suspend fun setOwnerAvatar(upload: AvatarUpload?): AiAvatar? =
        request("/owner/profile/avatar", "POST", JSONObject().put("image",
            if (upload == null) JSONObject.NULL
            else JSONObject().put("mime_type", "image/jpeg").put("base64", upload.base64)
        )).optJSONObject("avatar")?.let { AiAvatar.from(it) }

    // RECONSTRUCTED: the release writes the FULL member list with metadata_version.
    suspend fun removeRoomMember(room: String, actor: String, metadataVersion: Int): JSONObject {
        val remaining = roomRoster().rooms.firstOrNull { it.id == room }?.members
            ?.filter { it.first != actor }?.map { it.first } ?: throw ApiException(0, "room_unavailable")
        return request("/owner/rooms/" + enc(room) + "/members", "POST", JSONObject()
            .put("members", JSONArray(remaining))
            .put("metadata_version", metadataVersion))
    }

    suspend fun roomPreferences(room: String): JSONObject = request("/owner/rooms/" + enc(room) + "/preferences")

    // RECONSTRUCTED: the release PUTs the full preferences object (client_id,
    // version, pinned, mute_mode, muted_until, archived, metadata_version when
    // archived changes). The rename action shares this route in the reconstruction;
    // the release's exact rename write path is not yet located (registered gap).
    suspend fun roomMutation(room: String, action: String, body: JSONObject): JSONObject {
        val prefs = roomPreferences(room)
        val merged = JSONObject(prefs.toString())
        val keys = body.keys()
        while (keys.hasNext()) { val k = keys.next(); merged.put(k, body.get(k)) }
        merged.put("client_id", java.util.UUID.randomUUID().toString())
        return request("/owner/rooms/" + enc(room) + "/preferences", "PUT", merged)
    }

    // RECONSTRUCTED: header helpers for direct (non-JSON-wrapper) owner requests.
    // The release's exact header set here is not verified (registered gap).
    private fun applyOriginHeaders(builder: Request.Builder, base: String, method: String) {
        builder.header("Accept", "application/json")
        builder.header("Origin", base)
        if (method != "GET") csrf?.let { builder.header("X-CSRF-Token", it) }
    }

    private fun checkSession() {
        if (session.cookie.isNullOrBlank()) throw SessionEndedException("owner_session_required")
    }

    // RECONSTRUCTED: exact release routes - GET /owner/events/<id> and
    // POST /read-events {thread_key, event_ids} (K0/J0).
    suspend fun event(id: String): JSONObject = request("/owner/events/" + enc(id))

    suspend fun markReadEvents(threadKey: String, eventIds: List<String>): JSONObject =
        request("/read-events", "POST", JSONObject()
            .put("thread_key", threadKey)
            .put("event_ids", JSONArray(eventIds)))

    suspend fun renewOnOpen(): JSONObject = request("/owner/session/renew", "POST")

    // RECONSTRUCTED Nov 23, 2026: verified download. Streams through the app's own
    // client, checks byte size, then SHA-256 when a digest was published. A failed
    // integrity check deletes the partial file; the cookie never leaves the app.
    suspend fun verifiedDownload(path: String, local: java.io.File, size: Long, digest: String?, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl() + path).header("Cache-Control", "no-store").get()
            applyOriginHeaders(builder, baseUrl(), "GET")
            client.newCall(builder.build()).execute().use { response ->
                checkSession()
                if (!response.isSuccessful) {
                    if (response.code == 401) throw SessionEndedException("owner_session_required")
                    throw ApiException(response.code, "download_failed")
                }
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                var done = 0L
                val total = response.body?.contentLength() ?: 0L
                response.body?.byteStream()?.use { input ->
                    local.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); sha.update(buf, 0, n); done += n
                            onProgress(done, total)
                        }
                    }
                } ?: throw ApiException(response.code, "download_failed")
                val expected = UpdateCheck.parseDigest(digest)
                val okSize = done == size
                val okDigest = expected != null && sha.digest().joinToString("") { "%02x".format(it) } == expected
                if (!okSize || (expected != null && !okDigest)) {
                    local.delete()
                    throw ApiException(0, "integrity_check_failed")
                }
                reachable = true
                expected != null
            }
        }

    suspend fun enroll(token: String, deviceLabel: String): Unit = withContext(Dispatchers.IO) {
        raw("/owner/enroll", "POST", JSONObject()
            .put("token", token)
            .put("device_label", deviceLabel))
        bootstrap()
        Unit
    }

    suspend fun rooms(): JSONArray = request("/owner/rooms").optJSONArray("rooms") ?: JSONArray()

    // RECONSTRUCTED Nov 23, 2026: typed roster (GET /owner/rooms); envelope keys
    // recovered from the exact private12 release build.
    suspend fun roomRoster(): RoomRoster = RoomRoster.from(request("/owner/rooms"))

    suspend fun waitingPage(room: String, cursor: String? = null, limit: Int = 20): JSONObject =
        request("/owner/rooms/" + enc(room) + "/waiting?limit=" + limit +
            (if (cursor != null) "&cursor=" + enc(cursor) else ""))

    suspend fun roomEvents(room: String, after: String? = null, before: String? = null, limit: Int = 50): JSONObject =
        request("/owner/rooms/" + enc(room) + "/events?limit=" + limit +
            (if (after != null) "&after=" + enc(after) else "") +
            (if (before != null) "&before=" + enc(before) else ""))

    suspend fun threadEvents(thread: String, after: String? = null, limit: Int = 50): JSONObject =
        request("/owner/threads/" + enc(thread) + "?limit=" + limit +
            (if (after != null) "&after=" + enc(after) else ""))

    suspend fun post(room: String, clientId: String, text: String, threadKey: String?, parentId: String?, mentions: List<String>? = null): JSONObject =
        request("/rooms/" + enc(room) + "/posts", "POST", JSONObject()
            .put("client_id", clientId)
            .put("text", text)
            .put("thread_key", threadKey ?: JSONObject.NULL)
            .put("parent_id", parentId ?: JSONObject.NULL))

    suspend fun markRead(threadKey: String, seq: Long): JSONObject =
        request("/read", "POST", JSONObject()
            .put("thread_key", threadKey)
            .put("last_read_seq", seq))

    suspend fun search(query: String, room: String? = null, limit: Int = 20): JSONArray =
        request("/owner/search?q=" + enc(query) + "&limit=" + limit +
            (if (room != null) "&room=" + enc(room) else ""))
            .optJSONArray("results") ?: JSONArray()

    suspend fun files(room: String, limit: Int = 50): JSONArray =
        request("/owner/rooms/" + enc(room) + "/files?limit=" + limit)
            .optJSONArray("files") ?: JSONArray()

    /** Returns (bytes, mimeType) for an image preview, or throws. */
    suspend fun filePreview(id: String, maxBytes: Int = 10 * 1024 * 1024): Pair<ByteArray, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl() + "/owner/files/" + enc(id))
                .header("Accept", "*/*")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw SessionEndedException("unauthorized")
                if (!response.isSuccessful) throw ApiException(response.code, "preview_failed")
                val type = (response.header("Content-Type") ?: "").split(';')[0]
                if (type !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    throw ApiException(0, "unsupported_preview_type")
                }
                val bytes = response.body!!.bytes()
                if (bytes.isEmpty() || bytes.size > maxBytes) throw ApiException(0, "preview_size_limit")
                bytes to type
            }
        }

    // RECONSTRUCTED Nov 23, 2026: exact release routes (fresh decompile
    // K0/J0.o and K0/J0.L from APK sha256 f6b7e589...):
    // POST /owner/rules {room_id (JSON null for global), text, client_id, plus
    // scope=all_rooms|room only when the global_owner_rules capability is
    // present}; the release asserts the owner_rules/global_owner_rules
    // capability before calling. And
    // POST /owner/ais/<id>/join-codes/<jid>/report-used (empty body).
    suspend fun proposeRule(room: String?, text: String, clientId: String): JSONObject {
        require(hasFeature(if (room == null) "global_owner_rules" else "owner_rules")) { "capability_required" }
        val body = JSONObject()
            .put("room_id", room ?: JSONObject.NULL)
            .put("text", text)
            .put("client_id", clientId)
        if (hasFeature("global_owner_rules")) body.put("scope", if (room == null) "all_rooms" else "room")
        return request("/owner/rules", "POST", body)
    }

    suspend fun reportJoinCodeUsed(actor: String, joinId: String): JSONObject =
        request("/owner/ais/" + enc(actor) + "/join-codes/" + enc(joinId) + "/report-used", "POST", JSONObject())

    suspend fun rules(room: String? = null): JSONObject =
        request("/owner/rules" + (if (room != null) "?room=" + enc(room) else ""))

    suspend fun ruleHistory(ruleId: String): JSONObject =
        request("/owner/rules/" + enc(ruleId) + "/history")

    /** decision "edit" sends the rule back with notes only; the server rejects any other text field. */
    suspend fun ruleDecision(ruleId: String, version: Int, decision: String, notes: String? = null): JSONObject =
        request("/owner/rules/" + enc(ruleId) + "/decision", "POST", JSONObject()
            .put("version", version)
            .put("decision", decision)
            .also { if (notes != null) it.put("notes", notes) })

    suspend fun recordReread(room: String): JSONObject =
        request("/rules/reread", "POST", JSONObject()
            .put("room", room)
            .put("at", java.time.Instant.now().toString()))

    suspend fun muteRoom(room: String, muted: Boolean): JSONObject =
        request("/push/mute", "PUT", JSONObject()
            .put("room_id", room)
            .put("muted", muted)
            .put("until", JSONObject.NULL))

    suspend fun aiKeys(): List<AiKey> = request("/owner/ai-keys").requiredArray("keys").objects().map {AiKey.from(it)}

    // RECONSTRUCTED Nov 23, 2026: not original source. Behavior recovered from
    // the exact private12 release build (APK sha256
    // f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d).
    // Requires fresh review and tests before any release or equivalence claim.

    /** Sends back exactly the displayed values plus the typed fingerprint ending; the server rejects any mismatch. */
    suspend fun approveAiKey(key:AiKey, suffix:String): JSONObject =
        request("/owner/ai-keys/" + enc(key.keyId) + "/approve", "POST", JSONObject()
            .put("key_id", key.keyId)
            .put("actor", key.actor)
            .put("public_key_sha256", key.publicKeySha256)
            .put("rooms", JSONArray(key.rooms))
            .put("fingerprint_confirmation", JSONObject()
                .put("matched", true)
                .put("suffix", suffix.trim().lowercase())))

    suspend fun denyAiKey(keyId:String): JSONObject =
        request("/owner/ai-keys/" + enc(keyId) + "/reject", "POST", JSONObject())

    /** Server-advertised feature flags (reconstructed member; population site still being verified). */
    val features=java.util.concurrent.ConcurrentHashMap<String,Boolean>()

    // RECONSTRUCTED Nov 23, 2026: population site recovered from the exact
    // private12 release build's feature-refresh worker: GET /owner/instance,
    // take its "features" object, every key becomes a boolean flag. The release
    // also renews the session when the server advertises "session_renew" (401 ->
    // session ended); that worker is still being recovered separately.
    suspend fun refreshFeatures() {
        val data = request("/owner/instance")
        val advertised = data.optJSONObject("features")
        val fresh = java.util.concurrent.ConcurrentHashMap<String,Boolean>()
        advertised?.keys()?.forEach { k -> fresh[k] = advertised.optBoolean(k, false) }
        features.clear()
        features.putAll(fresh)
    }
    fun hasFeature(name:String):Boolean = features[name]==true

    /** Private feedback: server stores it in the owner's Feedback room; client_id dedupes retries. */
    suspend fun sendFeedback(text:String, clientId:String): JSONObject =
        request("/owner/feedback", "POST", JSONObject()
            .put("client_id", clientId)
            .put("text", text))

    // RECONSTRUCTED: AisScreen needs AIs plus the room ids they can join;
    // paired fetch, same endpoints as the individual calls.
    suspend fun aisAndJoinRooms(): Pair<List<OwnerAi>, List<String>> =
        ais() to roomRoster().rooms.map { it.id }

    suspend fun ais(): List<OwnerAi> = request("/owner/ais").requiredArray("ais").objects().map {OwnerAi.from(it)}
    /** No idempotency key: never resend after an ambiguous failure; reload the list first. */
    suspend fun createAi(displayName:String, kind:String): OwnerAi =
        OwnerAi.from(request("/owner/ais", "POST", JSONObject().put("display_name", displayName).put("kind", kind)))
    suspend fun renameAi(actor:String, displayName:String): OwnerAi =
        OwnerAi.from(request("/owner/ais/" + enc(actor) + "/rename", "POST", JSONObject().put("display_name", displayName)))
    /** Additive and idempotent per the contract. Members are actor ids only. */
    suspend fun addRoomMembers(room:String, members:List<String>): JSONObject =
        request("/owner/rooms/" + enc(room) + "/members", "POST", JSONObject().put("members", JSONArray(members)))

    /** No idempotency key: callers must never resend after an ambiguous failure. */
    suspend fun createRoom(name:String, members:List<String>): JSONObject =
        request("/owner/rooms", "POST", JSONObject()
            .put("name", name)
            .put("icon", JSONObject.NULL)
            .put("members", JSONArray(members)))

    suspend fun sessions(): JSONArray = request("/owner/sessions").optJSONArray("sessions") ?: JSONArray()

    suspend fun revokeSession(id: String): JSONObject =
        request("/owner/sessions/" + enc(id), "DELETE")

    // RECONSTRUCTED: POST /push/unregister {token}, recovered from the exact
    // private12 release build's sign-out/token-cleanup path.
    suspend fun unregisterPushToken(token: String): JSONObject =
        request("/push/unregister", "POST", JSONObject().put("token", token))

    suspend fun logout() {
        try {
            request("/owner/logout", "POST", JSONObject())
        } finally {
            sessionData = null
            csrf = null
            session.clear()
        }
    }

    companion object {
        const val COOKIE_NAME = "__Host-homeroom"
        const val API_PREFIX = "/homeroom/v1"
        private val JSON = "application/json".toMediaType()

        fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}
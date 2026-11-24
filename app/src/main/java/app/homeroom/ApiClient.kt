package app.homeroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SessionEndedException(val reason: String?, val opGeneration: Long? = null) : Exception("session_ended")

class ApiException(val status: Int, val code: String, val retryAfter: Int = 0) : Exception(code)

/**
 * Speaks to the HomeRoom server API under /homeroom/v1.
 *
 * Auth is the __Host-homeroom session cookie. Non-GET requests carry the
 * CSRF token from the latest /owner/session bootstrap. A 409 session_rotated
 * or 403 csrf_failed is retried once after a fresh bootstrap; a 401 ends the
 * session and surfaces as SessionEndedException.
 */
class ApiClient(
    private val session: Session,
    // TESTABILITY SEAM (Nov 24, 2026): default-noop OkHttpClient.Builder
    // customizer so JVM unit tests can point TLS/timeouts at a MockWebServer.
    // The production path passes nothing; no TLS relaxation is added here.
    private val clientConfig: (OkHttpClient.Builder) -> Unit = {},
) {
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

    // ONE jarless client for every app request (I2 4ff0522 coherent
    // boundary): requests carry Cookie/CSRF explicitly from an immutable
    // Session.OpAuth snapshot captured at operation entry, and response
    // cookies are accepted explicitly, attributed to the operation's
    // generation (acceptResponseCookies). OkHttp's BridgeInterceptor would
    // replace an explicit Cookie header with live jar state - that mechanism
    // was the rebinding path, so no CookieJar exists at all.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .apply(clientConfig)
        .build()

    // RECONSTRUCTION-ORIGINAL hardening (not release-evidenced): the session
    // cookie only ever goes to the enrolled server's ORIGIN - scheme, host and
    // effective port all must match. Server-supplied absolute URLs (e.g.
    // avatar URLs) could otherwise carry the session to a foreign endpoint.
    // Internal so the mock harness can pin the boundary.
    internal fun sessionCookieFor(url: HttpUrl): String? {
        val value = session.cookie ?: return null
        return if (isEnrolledOrigin(url)) value else null
    }

    internal fun isEnrolledOrigin(url: HttpUrl): Boolean = isEnrolledOrigin(url, session.serverUrl)

    // OUTBOUND credential scope (I2 01d1f60 finding): the operation's own
    // destination decides whether credentials may be attached - never a
    // foreign absolute URL. Scheme + host + port must all match.
    internal fun isEnrolledOrigin(url: HttpUrl, serverUrl: String?): Boolean {
        val origin = enrolledOrigin(serverUrl) ?: return false
        return url.scheme == origin.first && url.host == origin.second && url.port == origin.third
    }

    // RECONSTRUCTION-ORIGINAL hardening (not release-evidenced): a session
    // cookie is only ACCEPTED from the enrolled server's origin. A foreign
    // host (e.g. an absolute avatar URL) must not overwrite enrolled session
    // state with a same-named cookie. Internal so the mock harness can pin it.
    // Every session-cookie acceptance is attributed to the OPERATION's
    // generation: a response from before sign-out is dropped by
    // Session.acceptCookieForGeneration under stateLock. Internal so the
    // mock harness can pin it.
    internal fun acceptSessionCookie(url: HttpUrl, cookies: List<Cookie>, opGeneration: Long): Boolean {
        if (!isEnrolledOrigin(url)) return false
        val cookie = cookies.firstOrNull { it.name == COOKIE_NAME } ?: return false
        return session.acceptCookieForGeneration(opGeneration, cookie.value)
    }

    // Explicit response-cookie acceptance for the jarless client: every
    // response passes here, attributed to the issuing operation's snapshot.
    private fun acceptResponseCookies(response: Response, auth: Session.OpAuth) {
        val url = response.request.url
        for (h in response.headers("Set-Cookie")) {
            val parsed = Cookie.parse(url, h) ?: continue
            if (parsed.name == COOKIE_NAME) {
                acceptSessionCookie(url, listOf(parsed), auth.generation)
            }
        }
    }

    private fun enrolledOrigin(serverUrl: String? = session.serverUrl): Triple<String, String, Int>? = try {
        val u = java.net.URI(serverUrl ?: return null)
        val host = u.host?.lowercase() ?: return null
        val port = if (u.port == -1) (if (u.scheme == "https") 443 else 80) else u.port
        Triple(u.scheme.lowercase(), host, port)
    } catch (e: Exception) { null }

    private fun baseUrl(auth: Session.OpAuth): String =
        (auth.serverUrl ?: throw ApiException(0, "no_server")).trimEnd('/') + API_PREFIX

    // Every app request is built ENTIRELY from the immutable OpAuth
    // snapshot captured at operation entry (I2 4ff0522 coherent boundary):
    // URL, Cookie and CSRF never come from live session state at dispatch
    // time, so a clear+re-enroll on any thread can only affect NEW
    // operations, never rebind an in-flight body.
    private fun raw(route: String, method: String = "GET", body: JSONObject? = null, auth: Session.OpAuth): JSONObject {
        val fullUrl = baseUrl(auth) + route
        val builder = Request.Builder()
            .url(fullUrl)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        auth.cookie?.let { builder.header("Cookie", COOKIE_NAME + "=" + it) }
        applyOriginHeaders(builder, fullUrl, method)
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, body.toString().toRequestBody(JSON))
        } else if (method != "GET") {
            builder.method(method, "{}".toRequestBody(JSON))
        } else {
            builder.get()
        }
        // Same-origin CSRF header: only ever attached to enrolled-server
        // requests, and only from the operation's own snapshot.
        if (method != "GET") auth.csrf?.let { builder.header("X-CSRF-Token", it) }
        execute(builder).use { response ->
            acceptResponseCookies(response, auth)
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
        val auth = session.authSnapshot()
        val builder=Request.Builder().url(baseUrl(auth)+"/owner/files/"+enc(id)).header("Cache-Control","no-store").get()
        auth.cookie?.let { builder.header("Cookie", COOKIE_NAME + "=" + it) }
        applyOriginHeaders(builder,baseUrl(auth)+"/owner/files/"+enc(id),"GET")
        execute(builder).use { response ->
            acceptResponseCookies(response, auth)
            checkSession(auth.generation)
            if(!response.isSuccessful) {
                if(response.code==401) throw SessionEndedException("owner_session_required", auth.generation)
                throw ApiException(response.code,"download_failed")
            }
            response.body?.byteStream()?.use {it.copyTo(out)} ?: throw ApiException(response.code,"download_failed")
            response.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
        }
    }

    suspend fun bootstrap(): JSONObject = bootMutex.withLock {
        withContext(Dispatchers.IO) {
            // Operation identity captured BEFORE the network (I2 recheck): a
            // sign-out completing mid-bootstrap must not let the dead
            // session's response write its fields into the new one. The
            // session-establishing GET is built from the immutable snapshot.
            val auth = session.authSnapshot()
            val opGen = auth.generation
            var data = try {
                raw("/owner/session", "GET", null, auth)
            } catch (e: ApiException) {
                // Same-body GET retry on rotation carries the SAME snapshot.
                if (e.status == 409 && e.code == "session_rotated") raw("/owner/session", "GET", null, auth) else throw e
            }
            session.withinGeneration(opGen) {
                csrf = data.optString("csrf").ifBlank { null }
                session.csrf = csrf
                sessionData = data
                session.ownerId = data.optString("owner_id").ifBlank { session.ownerId }
                session.ownerName = data.optString("owner_display_name").ifBlank { session.ownerName }
                session.sessionId = data.optString("session_id").ifBlank { session.sessionId }
            }
            data
        }
    }

    // Harness-only: fires inside dispatch, AFTER the request was fully built
    // from the operation's immutable snapshot and BEFORE it hits the network,
    // so tests can interleave a clear+re-enroll at the last point where a
    // live-bound implementation could still rebind. Never set in production.
    internal var preDispatchHook: (() -> Unit)? = null

    private fun execute(builder: Request.Builder): Response {
        preDispatchHook?.invoke()
        return client.newCall(builder.build()).execute()
    }

    // Harness-only: interleave hooks for the explicit-recovery branch.
    // recoveryTestHook fires before the recovery bootstrap; preRetryBuildHook
    // fires after the final identity check, immediately before the retry is
    // built and sent from its immutable snapshot. Never set in production.
    internal var recoveryTestHook: (() -> Unit)? = null
    internal var preRetryBuildHook: (() -> Unit)? = null

    suspend fun request(route: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        // Initiating identity at ACTUAL request entry, before any dispatcher
        // handoff or wait (I2 timing qualification): work that was queued
        // before this call cannot capture a newer session than the caller
        // that initiated this operation intended.
        return requestFor(session.authSnapshot(), route, method, body)
    }

    // Caller-bound request (I2 composer identity boundary): the CALLER's
    // initiating identity is threaded to actual dispatch - credentials are
    // adopted only while the session still matches that identity, so a
    // re-enroll mid-flow can never carry the caller's old operation (IDs,
    // bytes, text) to the NEW account/server. A moved identity rejects with
    // stale_operation, which is NOT SessionEndedException: the new session
    // is healthy and must not be torn down.
    suspend fun requestFor(initiating: Session.OpAuth, route: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        return withContext(Dispatchers.IO) {
            // Operation identity captured at ENTRY (I2 final, operation
            // rebinding): if clear+re-enroll completes while the first attempt
            // is in flight, the recovery path must NOT bootstrap the new
            // session and resend the original body with new credentials to a
            // new destination - stale work is rejected, never rebound. This is
            // ApiException("stale_operation"), NOT SessionEndedException: the
            // NEW session is healthy and must not be torn down.
            // Identity captured BEFORE any fallible/suspending wait (I2
            // final entry trace): a clear+re-enroll completing during the
            // bootstrap wait must not let this operation's body acquire a
            // new session or destination.
            val entryGen = initiating.generation
            val entryUrl = initiating.serverUrl
            if (sessionData == null) bootstrap()
            // Fresh credentials (post-bootstrap CSRF) are adopted only for
            // the SAME identity; a moved identity rejects the operation.
            val auth = session.authIfUnchanged(entryGen, entryUrl)
                ?: throw ApiException(0, "stale_operation")
            try {
                raw(route, method, body, auth).also {
                    reachable = true
                    lastSuccessfulRefresh = java.time.Instant.now().toString()
                }
            } catch (e: ApiException) {
                if ((e.status == 409 && e.code == "session_rotated") ||
                    (e.status == 403 && e.code == "csrf_failed")
                ) {
                    recoveryTestHook?.invoke()
                    if (!session.unchangedSince(entryGen, entryUrl)) throw ApiException(0, "stale_operation")
                    sessionData = null
                    try { bootstrap() } catch (b: ApiException) {
                        if (b.status == 401) throw SessionEndedException(b.code, entryGen); throw b
                    }
                    // The retry carries an IMMUTABLE snapshot read atomically
                    // with the identity check (I2 final): it can only go to
                    // the ORIGINAL destination with credentials valid for the
                    // operation's generation (authIfUnchanged refreshes the
                    // snapshot after the recovery bootstrap).
                    val retryAuth = session.authIfUnchanged(entryGen, entryUrl)
                        ?: throw ApiException(0, "stale_operation")
                    preRetryBuildHook?.invoke()
                    try {
                        raw(route, method, body, retryAuth).also {
                            reachable = true
                            lastSuccessfulRefresh = java.time.Instant.now().toString()
                        }
                    } catch (again: ApiException) {
                        reachable = false
                        if (again.status == 401) throw SessionEndedException(again.code, entryGen)
                        throw again
                    }
                } else {
                    reachable = false
                    if (e.status == 401) throw SessionEndedException(e.code, entryGen)
                    throw e
                }
            } catch (e: java.io.IOException) {
                reachable = false
                throw e
            }
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
    suspend fun uploadChunk(uploadId: String, offset: Long, chunk: ByteArray, sha256Hex: String, initiating: Session.OpAuth? = null): JSONObject =
        withContext(Dispatchers.IO) {
            // Caller-bound when initiating is supplied (I2 composer identity):
            // the chunk bytes only ever travel under credentials still valid
            // for the operation that staged them.
            val auth = if (initiating != null)
                session.authIfUnchanged(initiating.generation, initiating.serverUrl)
                    ?: throw ApiException(0, "stale_operation")
            else session.authSnapshot()
            val fullUrl = baseUrl(auth) + "/owner/uploads/" + enc(uploadId) + "/chunks/" + offset
            val builder = Request.Builder()
                .url(fullUrl)
                .header("X-Chunk-SHA256", sha256Hex)
                .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
            auth.cookie?.let { builder.header("Cookie", COOKIE_NAME + "=" + it) }
            auth.csrf?.let { builder.header("X-CSRF-Token", it) }
            applyOriginHeaders(builder, fullUrl, "PUT")
            execute(builder).use { response ->
                acceptResponseCookies(response, auth)
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) throw SessionEndedException("owner_session_required", auth.generation)
                    // cp42 crash recovery: a 409 upload_bytes_inconsistent
                    // carries requires_restart/retry_same_upload=false and the
                    // client must abandon this upload, never auto-resume it.
                    val err = try { JSONObject(text).optString("error", "") } catch (e: Exception) { "" }
                    throw ApiException(response.code, if (err.isNotEmpty()) err else "upload_failed")
                }
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            }
        }

    // RECONSTRUCTED: held-1.5 OUTER wire contract (owning source outgoing15.py
    // + conversation14 dispatch insertion, sections A/B of the recovered
    // wrapper reconciliation): a post body carrying client_created_at enters
    // the 1.5 offline-identity wrapper, which VALIDATES the timestamp
    // (-30d -> 410 outgoing_expired/requires_user_resend; >+300s -> 400
    // future_created_at), hashes the ORIGINAL body for idempotency, strips
    // the timestamp, namespaces client_id, then reenters conversation14.post
    // and (for upload_ids) files15.post, whose inner allowlist is
    // {client_id, text, thread_key, parent_id, mentions, asks_owner,
    // upload_ids}. upload_ids: max 4, unique strings; empty text allowed
    // when uploads attach. The wrapper also records the owner outgoing ID
    // for the ambiguous-send recovery lookup. The retry must keep the whole
    // original body byte-identical (composer locks payload + identities).
    suspend fun postWithUploads(room: String, clientId: String, created: String?, text: String,
                                threadKey: String?, parentId: String?, mentions: List<String>, uploadIds: List<String>, initiating: Session.OpAuth? = null): JSONObject {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("client_created_at", created ?: JSONObject.NULL)
            .put("text", text)
            .put("thread_key", threadKey ?: JSONObject.NULL)
            .put("parent_id", parentId ?: JSONObject.NULL)
            .put("upload_ids", JSONArray(uploadIds))
        return if (initiating != null) requestFor(initiating, "/rooms/" + enc(room) + "/posts", "POST", body)
        else request("/rooms/" + enc(room) + "/posts", "POST", body)
    }

    // RECONSTRUCTED: avatar bytes through the app's own client (cookie stays in-app).
    suspend fun avatarBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val auth = session.authSnapshot()
        // RECONSTRUCTION-ORIGINAL hardening (release behavior unverified):
        // absolute avatar URLs must be https, and downloads are size-capped.
        // The Cookie header is attached explicitly below only for requests
        // built from the enrolled-server snapshot; a foreign absolute URL
        // receives no credentials.
        val full = if (url.startsWith("http")) {
            if (!url.startsWith("https://")) throw ApiException(0, "avatar_url_not_https")
            url
        } else if (url.startsWith("/")) {
            // Production 1.4 sends ROOT-relative avatar URLs
            // (/homeroom/v1/owner/profile/avatar); resolve against the server
            // root, not the API base (would double the /homeroom/v1 prefix).
            (auth.serverUrl ?: throw ApiException(0, "no_server")).trimEnd('/') + url
        } else baseUrl(auth) + url
        val req = Request.Builder().url(full).get()
        // Credentials are attached ONLY when the resolved destination is the
        // enrolled origin of THIS operation's snapshot: an absolute avatar
        // URL on a foreign host receives no session cookie (the incoming-
        // origin guard never protected the outgoing direction).
        if (isEnrolledOrigin(full.toHttpUrl(), auth.serverUrl)) {
            auth.cookie?.let { req.header("Cookie", COOKIE_NAME + "=" + it) }
        }
        applyOriginHeaders(req, full, "GET")
        execute(req).use { response ->
            acceptResponseCookies(response, auth)
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
        // Grounded in the production 1.4 server source (owner_room manage
        // route): rename and archive are DEDICATED POST routes whose exact
        // bodies are {display_name, metadata_version} and {archived,
        // metadata_version}, CAS-checked server-side. The preferences PUT
        // below remains the held-1.5 path for mute/pin (decompile-grounded,
        // absent from the 1.4 router).
        if (action == "rename" || action == "archive") {
            val exact = JSONObject()
            if (action == "rename") exact.put("display_name", body.getString("display_name"))
            else exact.put("archived", body.getBoolean("archived"))
            exact.put("metadata_version", body.getInt("metadata_version"))
            return request("/owner/rooms/" + enc(room) + "/" + action, "POST", exact)
        }
        // Owning contract (preferences15.put): the PUT body must be EXACTLY
        // {client_id, version, pinned, mute_mode, muted_until, archived} with
        // metadata_version only when the shared archive state changes - a GET
        // echo would send response-only keys (room_id, archive_scope) and a
        // stale muted_until on off/forever, both 400 bad_body server-side.
        // Build the mutation body from the allowlist, never by merging.
        val prefs = roomPreferences(room)
        val exact = JSONObject()
            .put("client_id", java.util.UUID.randomUUID().toString())
            .put("version", prefs.getInt("version"))
            .put("pinned", prefs.getBoolean("pinned"))
            .put("mute_mode", if (body.has("mute_mode")) body.getString("mute_mode") else prefs.getString("mute_mode"))
            .put("muted_until", if (body.has("muted_until")) body.get("muted_until") else JSONObject.NULL)
            .put("archived", prefs.getBoolean("archived"))
        if (body.has("archived") && body.getBoolean("archived") != prefs.getBoolean("archived"))
            exact.put("archived", body.getBoolean("archived")).put("metadata_version", prefs.getInt("metadata_version"))
        return request("/owner/rooms/" + enc(room) + "/preferences", "PUT", exact)
    }

    // RECONSTRUCTED from the exact private12 release build (Z1/b.c, verified
    // against the production 1.4 server source, which 403s bad_origin without
    // these): EVERY request carries Sec-Fetch-Site: same-origin, Sec-Fetch-Mode:
    // cors and Sec-Fetch-Dest: empty; non-GET requests also carry Origin =
    // scheme://host[:port] of the request URL (no path).

    private fun checkSession(opGeneration: Long? = null) {
        if (session.cookie.isNullOrBlank()) throw SessionEndedException("owner_session_required", opGeneration)
    }

    // RECONSTRUCTED: exact release routes - GET /owner/events/<id> and
    // POST /read-events {thread_key, event_ids} (K0/J0).
    suspend fun event(id: String, initiating: Session.OpAuth? = null): JSONObject =
        if (initiating != null) requestFor(initiating, "/owner/events/" + enc(id)) else request("/owner/events/" + enc(id))

    // Direct single-shot event fetch (RotationHttpTest lineage): never
    // bootstraps, renews or retries - a 409 session_rotated propagates so the
    // caller cannot invent recovery with a retired secret. Serialized behind
    // an in-flight renewOnOpen via renewMutex so a concurrent rotation lands
    // first and this fetch adopts the renewed credentials; 401 ends the
    // session. Currently a test seam - prod callers use the recovering
    // event(); coverage kept honest (no prod caller claimed).
    suspend fun eventDirect(id: String): JSONObject {
        // Initiating identity captured at ACTUAL entry, BEFORE the renewMutex
        // wait (I2 timing qualification, same boundary as request()): the wait
        // only serializes against an in-flight rotation; credentials refresh
        // solely within the SAME generation/destination, never a rebind.
        val initiating = session.authSnapshot()
        return renewMutex.withLock {
            val auth = session.authIfUnchanged(initiating.generation, initiating.serverUrl)
                ?: throw ApiException(0, "stale_operation")
            if (auth.cookie.isNullOrBlank()) throw SessionEndedException("owner_session_required", auth.generation)
            withContext(Dispatchers.IO) {
                try {
                    raw("/owner/events/" + enc(id), auth = auth)
                } catch (e: ApiException) {
                    if (e.status == 401) throw SessionEndedException(e.code, auth.generation)
                    throw e
                }
            }
        }
    }

    suspend fun markReadEvents(threadKey: String, eventIds: List<String>): JSONObject =
        request("/read-events", "POST", JSONObject()
            .put("thread_key", threadKey)
            .put("event_ids", JSONArray(eventIds)))

    // RECOVERED Nov 24, 2026 from the exact Nov 15 application source (operator
    // readback, historical-app-feature-lifecycle-42d75768): the release's
    // foreground feature/session worker, called on enrollment and on resume.
    // 1s foreground debounce; bootstraps when needed (401 -> session ended);
    // GET /owner/instance replaces the feature map (a non-401 error leaves it
    // empty); when the server advertises "session_renew", POST
    // /owner/session/renew commits the rotated cookie+CSRF under the session
    // generation guard before later requests run ("requests wait until fresh
    // credentials are committed"). LABELED DIVERGENCE: the historical build
    // serialized ALL requests on the same mutex as this worker; this tree's
    // request() path is not mutex-covered, so only renew calls serialize here.
    private val renewMutex = Mutex()
    private var lastForegroundRenew: Long = 0L

    suspend fun renewOnOpen() {
        // Initiating identity before the renewMutex wait (I2 timing
        // qualification): a pass queued behind another renew must not bind
        // to a session newer than the one its caller acted on.
        val initiating = session.authSnapshot()
        renewMutex.withLock {
        // Divergence from the historical source: System.nanoTime() instead of
        // android.os.SystemClock.elapsedRealtime() so the file compiles in the
        // pure-JVM mock harness; both are monotonic clocks.
        val now = System.nanoTime() / 1_000_000L
        if (lastForegroundRenew != 0L && now - lastForegroundRenew < 1000L) return@withLock
        lastForegroundRenew = now
        // Operation identity captured BEFORE any network (I2 recheck of
        // 93a5b8b): capturing after the response would accept a pre-sign-out
        // response under the NEW generation.
        // Identity captured BEFORE the bootstrap wait (I2 final entry
        // trace); fresh credentials are adopted only for the SAME identity,
        // and the bootstrap 401 is attributed to it.
        val entryGen = initiating.generation
        val entryUrl = initiating.serverUrl
        if (sessionData == null) try { bootstrap() } catch (e: ApiException) {
            if (e.status == 401) throw SessionEndedException(e.code, entryGen); throw e
        }
        val auth = session.authIfUnchanged(entryGen, entryUrl)
            ?: throw ApiException(0, "stale_operation")
        val opGen = auth.generation
        withContext(Dispatchers.IO) {
            val flags = try { raw("/owner/instance", "GET", null, auth).optJSONObject("features") } catch (e: ApiException) {
                if (e.status == 401) throw SessionEndedException(e.code, opGen); null
            }
            features.clear()
            flags?.keys()?.forEach { features[it] = flags.optBoolean(it, false) }
            if (features["session_renew"] == true) {
                // Re-snapshot the SAME generation: the instance response may
                // have rotated the cookie (accepted under this generation),
                // and the renew must carry the freshest credentials of the
                // same identity, never a rebound one.
                val renewAuth = session.authIfUnchanged(auth.generation, auth.serverUrl)
                    ?: throw ApiException(0, "stale_operation")
                val data = try { raw("/owner/session/renew", "POST", JSONObject(), renewAuth) } catch (e: ApiException) {
                    if (e.status == 401) throw SessionEndedException(e.code, opGen); throw e
                }
                val freshCsrf = data.optString("csrf").ifBlank { csrf }
                session.withinGeneration(opGen) {
                    session.replaceCredentials(session.cookie, freshCsrf)
                    csrf = freshCsrf
                    sessionData = data
                }
            }
        }
        }
    }

    // RECONSTRUCTED Nov 23, 2026: verified download. Streams through the app's own
    // client, checks byte size, then SHA-256 when a digest was published. A failed
    // integrity check deletes the partial file; the cookie never leaves the app.
    suspend fun verifiedDownload(path: String, local: java.io.File, size: Long, digest: String?, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val auth = session.authSnapshot()
            val builder = Request.Builder().url(baseUrl(auth) + path).header("Cache-Control", "no-store").get()
            auth.cookie?.let { builder.header("Cookie", COOKIE_NAME + "=" + it) }
            applyOriginHeaders(builder, baseUrl(auth) + path, "GET")
            execute(builder).use { response ->
                acceptResponseCookies(response, auth)
                checkSession(auth.generation)
                if (!response.isSuccessful) {
                    if (response.code == 401) throw SessionEndedException("owner_session_required", auth.generation)
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

    // Original-lineage 3-arg shape (grounded by the Nov 15 SetupChecklist
    // source usage and the SetupHttpTest call sites): the accepted callback
    // fires once the server has accepted the enrollment, BEFORE the bootstrap
    // verification completes, so setup can show sign-in checking in between.
    suspend fun enroll(token: String, deviceLabel: String, accepted: suspend () -> Unit): Unit = withContext(Dispatchers.IO) {
        raw("/owner/enroll", "POST", JSONObject()
            .put("token", token)
            .put("device_label", deviceLabel), session.authSnapshot())
        // Session-install atomicity: the callback is UI-facing; its failure
        // must not strand a server-enrolled device without a local bootstrap.
        // Bootstrap runs regardless, then a callback failure still propagates.
        val callbackFailure = runCatching { accepted() }.exceptionOrNull()
        bootstrap()
        callbackFailure?.let { throw it }
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
            val auth = session.authSnapshot()
            val previewUrl = baseUrl(auth) + "/owner/files/" + enc(id)
            val builder = Request.Builder()
                .url(previewUrl)
                .header("Accept", "*/*")
                .get()
                .apply { applyOriginHeaders(this, previewUrl, "GET") }
            auth.cookie?.let { builder.header("Cookie", COOKIE_NAME + "=" + it) }
            execute(builder).use { response ->
                acceptResponseCookies(response, auth)
                if (response.code == 401) throw SessionEndedException("unauthorized", auth.generation)
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

    // Receipt hints never bootstrap or renew a session (actual original
    // source, patch read Nov 11 10:51:34 EST; adapted to the current OpAuth
    // boundary: identity snapshot captured at entry, 401 -> session ended).
    suspend fun testReceiptDirect(test: String, stage: String, client: String): JSONObject = withContext(Dispatchers.IO) {
        val auth = session.authSnapshot()
        if (auth.cookie.isNullOrBlank()) throw SessionEndedException("owner_session_required", auth.generation)
        try {
            raw("/owner/status/test-alerts/" + enc(test) + "/receipts", "POST",
                JSONObject().put("client_id", client).put("stage", stage), auth)
        } catch (e: ApiException) {
            if (e.status == 401) throw SessionEndedException(e.code, auth.generation)
            throw e
        }
    }

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

    /** Server-advertised feature flags, populated by renewOnOpen() (recovered
        foreground worker; was previously never populated - no call site existed). */
    val features=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
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

        // Stateless header helper; original-lineage tests call it
        // companion-style (OriginHeadersTest), so it lives here as internal.
        internal fun applyOriginHeaders(builder: Request.Builder, url: String, method: String) {
            val u = java.net.URI(url)
            val defaultPort = if (u.scheme == "https") 443 else if (u.scheme == "http") 80 else -1
            val origin = u.scheme + "://" + u.host + (if (u.port != -1 && u.port != defaultPort) ":" + u.port else "")
            if (method != "GET") builder.header("Origin", origin)
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Dest", "empty")
        }
    }
}
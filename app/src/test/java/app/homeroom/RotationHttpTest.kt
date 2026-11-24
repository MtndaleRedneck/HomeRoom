package app.homeroom

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RotationHttpTest {
    private fun fixture(test:suspend kotlinx.coroutines.CoroutineScope.(MockWebServer,Session,()->ApiClient,(Session)->ApiClient)->Unit)=runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.start()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        Session.resetRenewalStateForTests(app)  // renewal state is store-scoped; isolate each test
        val session=Session(app);session.clear()
        session.serverUrl=server.url("/").toString().trimEnd('/');session.cookie="old-secret";session.csrf="old-csrf";session.sessionId="same-session"
        val client={ApiClient(session) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(2,TimeUnit.SECONDS)}}
        val clientFor={s:Session -> ApiClient(s) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(2,TimeUnit.SECONDS)}}
        try {this.test(server,session,client,clientFor)} finally {server.shutdown();session.clear()}
    }
    private fun bootstrap()=MockResponse().setBody("""{"csrf":"new-csrf","session_id":"same-session"}""").addHeader("Set-Cookie","__Host-homeroom=new-secret; Path=/; Secure; HttpOnly")
    @Test fun successfulSevenDayRotation()=fixture {server,session,client,_ ->
        server.enqueue(bootstrap())
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf","session_id":"same-session"}"""))
        client().renewOnOpen()
        assertEquals("new-secret",session.cookie);assertEquals("renewed-csrf",session.csrf)
        assertEquals("/homeroom/v1/owner/session",server.takeRequest().path)
        val instance=server.takeRequest();assertTrue(instance.getHeader("Cookie")!!.contains("new-secret"))
        val renew=server.takeRequest();assertEquals("new-csrf",renew.getHeader("X-CSRF-Token"));assertEquals("POST",renew.method)
    }
    @Test fun lostRotationResponseDoesNotInventRecovery()=fixture {server,session,client,_ ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"session_rotated"}"""))
        try {client().renewOnOpen();fail("response lost") } catch(_:Exception) {}
        assertEquals("old-secret",session.cookie);assertEquals("old-csrf",session.csrf)
        val prior=server.requestCount
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"session_rotated"}"""))
        try {client().eventDirect("e");fail("retired secret") } catch(e:ApiException) {assertEquals(409,e.status)}
        assertEquals(prior+1,server.requestCount)
    }
    @Test fun backgroundWaitsForForegroundRenewalAcrossClients()=fixture {server,session,client,_ ->
        server.enqueue(bootstrap())
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf"}""").setBodyDelay(250,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly"))
        server.enqueue(MockResponse().setBody("""{"event":{"event_id":"e"}}"""))
        val foreground=async(Dispatchers.IO) {client().renewOnOpen()}
        withContext(Dispatchers.IO) {repeat(3) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}}
        val background=async(Dispatchers.IO) {client().eventDirect("e")}
        foreground.await();background.await()
        val request=withContext(Dispatchers.IO) {server.takeRequest(3,TimeUnit.SECONDS)}!!
        assertEquals("/homeroom/v1/owner/events/e",request.path)
        assertTrue(request.getHeader("Cookie")!!.contains("renewed-secret"))
        assertEquals("renewed-csrf",session.csrf)
    }
    // Request-path coordination (mutation-sensitive): a request() call made
    // DURING an in-flight foreground renew must wait on the session's
    // renewal mutex and dispatch with the RENEWED credentials. Removing the
    // request-entry lock makes this fail (the request would go out with the
    // pre-renew cookie). Complements the eventDirect pin, which covers the
    // same mutex via the test seam only.
    @Test fun requestPathWaitsForForegroundRenewal()=fixture {server,session,client,_ ->
        val api=client()
        server.enqueue(bootstrap())
        api.bootstrap()
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // drain the prewarm arrival
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf"}""").setBodyDelay(500,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly"))
        server.enqueue(MockResponse().setBody("""{"entries":[]}"""))
        val renew=async(Dispatchers.IO) {api.renewOnOpen()}
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}
        val activity=async(Dispatchers.IO) {api.activity(null)}
        val request=withContext(Dispatchers.IO) {server.takeRequest(3,TimeUnit.SECONDS)}!!
        renew.await();activity.await()
        assertEquals("/homeroom/v1/owner/activity?limit=50",request.path)
        assertTrue(request.getHeader("Cookie")!!.contains("renewed-secret"))
    }
    // Late pre-rotation response (ChatGPT A2 closure ordering): the request
    // is dispatched BEFORE the renew but its response is held until AFTER
    // the rotation commits and the in-flight window closes. The poison
    // Set-Cookie must still be rejected - the dispatch-time rotation epoch
    // no longer matches. Fails against the in-flight-flag-only gate.
    @Test fun latePreRotationResponseCookieRejected()=fixture {server,session,client,_ ->
        val api=client()
        // Deterministic ordering (I2 b48512e): the poison response's HEADERS
        // are held behind a latch released only after the renew has
        // committed AND cleared the in-flight marker, so the in-flight gate
        // cannot do the drop - only the dispatch-epoch fence can. A pure
        // header delay is non-discriminating whenever the renew outlasts it.
        // Removing the epoch check fails this test at any renew duration.
        val poisonGate=java.util.concurrent.CountDownLatch(1)
        server.dispatcher=object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path=request.path ?: ""
                return when {
                    path.endsWith("/owner/session") -> bootstrap()
                    path.endsWith("/owner/instance") -> MockResponse().setBody("""{"features":{"session_renew":true}}""")
                    path.endsWith("/owner/session/renew") -> MockResponse().setBody("""{"csrf":"renewed-csrf"}""").addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly")
                    path.contains("/owner/uploads/") -> {
                        assertTrue("test sequencing: poison request must wait for the commit", poisonGate.await(10,TimeUnit.SECONDS))
                        MockResponse().setBody("""{"ok":true}""").addHeader("Set-Cookie","__Host-homeroom=poison; Path=/; Secure; HttpOnly")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        api.bootstrap()
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // drain the prewarm arrival
        val slow=async(Dispatchers.IO) {api.uploadChunk("u",0,byteArrayOf(1),"x")}  // dispatchEpoch captured pre-renew
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // upload dispatched
        api.renewOnOpen()  // commits renewed-secret, bumps the epoch, clears in-flight
        assertEquals("renewed-secret",session.cookie)
        poisonGate.countDown()  // NOW the late pre-rotation response arrives
        slow.await()
        assertEquals("renewed-secret",session.cookie)
        assertEquals("renewed-csrf",session.csrf)
    }

    // I2 b48512e finite: verifiedDownload bootstrapped WITHOUT the renewal
    // mutex, so its raw()/generation-only writes could overlap an in-flight
    // renew's commit. The bootstrap is now taken under the lock; while the
    // renew is gated server-side, apiB must issue NO request. Without the
    // lock the bootstrap dispatch makes requestCount 4 during the window.
    // I2 b48512e finite: verifiedDownload's bootstrap ran UNLOCKED, so its
    // raw()/generation-only writes could overlap an in-flight renew's commit
    // window. Bootstrap now runs under the renewal mutex (same as
    // requestFor/renewOnOpen), so a concurrent verifiedDownload must wait for
    // the renew COMMIT - pinned deterministically by delaying the renew
    // response BODY (the commit runs after the body is read) and asserting no
    // second-client request leaves while the commit is still pending.
    // Discriminates: without the mutex the bootstrap dispatches immediately
    // and the mid-window count is 4, not 3. Margins match the sibling
    // in-flight test (600ms body delay, 300ms assertion point).
    @Test fun verifiedDownloadBootstrapWaitsForRenewCommit()=fixture {server,session,client,_ ->
        val apiA=client()
        val apiB=client()
        server.enqueue(bootstrap())
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf"}""").setBodyDelay(600,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly"))
        server.enqueue(bootstrap())  // apiB's OWN bootstrap (sessionData is a per-ApiClient cache; only the mutex is shared)
        server.enqueue(MockResponse().setBody("file-bytes"))
        val renew=async(Dispatchers.IO) {apiA.renewOnOpen()}
        withContext(Dispatchers.IO) {repeat(3) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}}  // apiA bootstrap, instance, renew POST
        val tmp=java.io.File.createTempFile("dl-",".bin")
        val dl=async(Dispatchers.IO) {apiB.verifiedDownload("/owner/files/f",tmp,10,"sha256:13015a3cf02c05dafbefab3b331350db348e70e86f4e43e73f325473957f0a5c",{_,_->})}
        delay(300)  // renew headers consumed; body (and commit) still delayed
        assertEquals("no verifiedDownload request may leave before the renew commit",3,server.requestCount)
        renew.await()
        assertTrue(dl.await())
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // apiB bootstrap, post-commit
        val fileReq=withContext(Dispatchers.IO) {server.takeRequest(3,TimeUnit.SECONDS)}!!
        assertEquals("/homeroom/v1/owner/files/f",fileReq.path)
        tmp.delete()
    }

    // I2 be68ed6 finite: the app constructs SEPARATE Session wrappers over
    // the same prefs (MainActivity, messaging service, notification worker),
    // so coordination must hold across wrappers, not just ApiClients. This
    // fails with per-wrapper renewal state: wrapper B's eventDirect takes a
    // different mutex, never waits, and dispatches with the old cookie.
    @Test fun renewalCoordinationSharedAcrossSessionWrappers()=fixture {server,session,client,clientFor ->
        val sessionB=Session(org.robolectric.RuntimeEnvironment.getApplication())
        val apiB=clientFor(sessionB)
        server.enqueue(bootstrap())
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf"}""").setBodyDelay(250,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly"))
        server.enqueue(MockResponse().setBody("""{"event":{"event_id":"e"}}"""))
        val foreground=async(Dispatchers.IO) {client().renewOnOpen()}  // wrapper A renews
        withContext(Dispatchers.IO) {repeat(3) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}}
        val background=async(Dispatchers.IO) {apiB.eventDirect("e")}  // wrapper B fetches
        foreground.await();background.await()
        val request=withContext(Dispatchers.IO) {server.takeRequest(3,TimeUnit.SECONDS)}!!
        assertEquals("/homeroom/v1/owner/events/e",request.path)
        assertTrue("request from a separate Session wrapper adopted the rotated cookie",
            request.getHeader("Cookie")!!.contains("renewed-secret"))
        assertEquals("renewed-csrf",sessionB.csrf)
    }
    // Mid-renew commit gate (binary parity: J0.java `if (!this.k)` + s0's
    // explicit renew commit), pinned DETERMINISTICALLY on a non-mutex path
    // (uploadChunk streams outside the request-entry lock): the in-flight
    // response's poison Set-Cookie must be REJECTED while the renew is in
    // flight (asserted before the renew commits: cookie still holds the
    // bootstrap value), and the renew's own Set-Cookie must land via the
    // explicit captured commit. Margins: upload body 100ms, renew body
    // 600ms, so the poison arrives squarely inside the renew window.
    @Test fun rotationSurvivesInFlightResponseCookie()=fixture {server,session,client,_ ->
        val api=client()
        server.enqueue(bootstrap())
        api.bootstrap()
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // drain the prewarm arrival
        // Streaming requests now capture auth+epoch under the renewal mutex
        // (I2 be68ed6), so a mid-renew DISPATCH is impossible; the in-flight
        // gate's remaining job is responses to requests dispatched BEFORE
        // the renew acquired the mutex. The upload goes first; its poisoned
        // Set-Cookie headers arrive mid-renew (300ms into a 600ms renew
        // body delay) and must be dropped while the epoch still matches -
        // removing the in-flight gate fails the mid-window assert
        // (verified by mutation).
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setHeadersDelay(300,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=poison; Path=/; Secure; HttpOnly"))
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf"}""").setBodyDelay(600,TimeUnit.MILLISECONDS).addHeader("Set-Cookie","__Host-homeroom=renewed-secret; Path=/; Secure; HttpOnly"))
        val slow=async(Dispatchers.IO) {api.uploadChunk("u",0,byteArrayOf(1),"x")}
        withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}  // upload dispatched pre-renew
        val renew=async(Dispatchers.IO) {api.renewOnOpen()}
        withContext(Dispatchers.IO) {repeat(2) {assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}}  // instance + renew POST
        delay(450)  // poison evaluated mid-window (300ms), renew commits at ~600ms
        assertEquals("new-secret",session.cookie)  // poison dropped by the in-flight gate
        slow.await()
        renew.await()
        assertEquals("renewed-secret",session.cookie)
        assertEquals("renewed-csrf",session.csrf)
    }
    @Test fun revokedBackgroundSessionReturnsSessionEnded()=fixture {server,_,client,_ ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"session_ended"}"""))
        try {client().eventDirect("e");fail("revoked") } catch(e:SessionEndedException) {assertEquals("session_ended",e.reason)}
        assertEquals(1,server.requestCount)
    }
    // Missing-case fill (I2 09:57): the GET /owner/session rotation path must
    // drop BOTH the Set-Cookie header and the body CSRF when the operation's
    // generation went stale mid-flight (sign-out interleaved at the
    // harness-only preDispatchHook, after the request was built from the
    // immutable snapshot). withinGeneration skips the body-field commit and
    // acceptCookieForGeneration drops the header cookie, so a dead session's
    // rotation response restores nothing.
    @Test fun staleGenerationGetSessionResponseDropsCookieAndCsrf()=fixture {server,session,client,_ ->
        server.enqueue(MockResponse().setBody("""{"csrf":"stale-csrf","session_id":"same-session"}""").addHeader("Set-Cookie","__Host-homeroom=stale-secret; Path=/; Secure; HttpOnly"))
        val api=client()
        api.preDispatchHook = { session.clear() }
        try { api.bootstrap() } catch (_:Exception) {} finally { api.preDispatchHook = null }
        assertNull(session.cookie);assertNull(session.csrf)
    }
}

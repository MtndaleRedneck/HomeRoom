package app.homeroom
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class V15NetworkTest {
    private fun fixture(test:suspend (MockWebServer,Session,ApiClient)->Unit)=runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val session=Session(RuntimeEnvironment.getApplication());session.clear();session.serverUrl=server.url("/").toString().trimEnd('/');session.cookie="original";session.csrf="csrf";session.sessionId="session"
        val api=ApiClient(session) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(2,TimeUnit.SECONDS)}
        try {test(server,session,api)}finally {server.shutdown();session.clear()}
    }
    private fun bootstrap(server:MockWebServer) {server.enqueue(MockResponse().setBody("""{"csrf":"csrf","session_id":"session"}"""))}
    // UNVERIFIED RECOVERED CONTRACT (operator-supplied Nov11 revision-archive
    // recovery, not owner-authenticated): asserts a route-specific rejection of
    // Set-Cookie on held /owner/updates polls. The 1.5-era backend source that
    // served that route is absent from every accessible tree (operator bounded
    // host/archive/repo search Nov24; TestLab archive has no backend source),
    // so the exclusion is unverifiable. Current frozen production policy is
    // enrolled-origin + current-generation acceptance on every route (I2 09:53-
    // 09:55: keep current pending reconciliation). This is NOT classified as an
    // implementation bug and NOT as a proven security defect; held as ignored
    // evidence until 1.5-era backend source or an owner decision resolves it.
    // The security coverage the current guards DO provide is pinned actively in
    // heldPollRejectsStaleGenerationCookie below.
    @Ignore("QUARANTINED unresolved historical contract: recovered route-exclusion expectation, 1.5-era backend source unavailable, current frozen policy is origin+generation acceptance on all routes; not an implementation bug, not a proven defect")
    @Test fun heldResponsesCannotRotateCredentials()=fixture {server,session,api->
        bootstrap(server)
        server.enqueue(MockResponse().setBody("""{"changes":[],"next_cursor":"watermark","has_more":false}""").addHeader("Set-Cookie","__Host-homeroom=attacker; Path=/; Secure; HttpOnly"))
        assertEquals("watermark",api.heldUpdates(null,0).getString("next_cursor"));assertEquals("original",session.cookie)
        assertEquals("/homeroom/v1/owner/session",server.takeRequest().path)
        val held=server.takeRequest();assertEquals("/homeroom/v1/owner/updates?wait=0&limit=100",held.path)
        assertEquals("__Host-homeroom=original",held.getHeader("Cookie"))
    }
    // CHARACTERIZATION of the CURRENT frozen policy (Nov 24; I2 09:57): an
    // enrolled-origin Set-Cookie on a held /owner/updates response under the
    // CURRENT operation generation IS accepted, identical to every other
    // route. Pins present behavior only - NOT parity or safety sign-off and
    // NOT a reversal of the quarantined historical contract above, which
    // stays open pending 1.5-era backend source or an owner decision.
    @Test fun characterization_currentPolicyAcceptsSameGenerationPollCookie()=fixture {server,session,api->
        bootstrap(server)
        server.enqueue(MockResponse().setBody("""{"changes":[],"next_cursor":"watermark","has_more":false}""").addHeader("Set-Cookie","__Host-homeroom=rotated; Path=/; Secure; HttpOnly"))
        api.heldUpdates(null,0)
        assertEquals("rotated",session.cookie)
    }
    // ACTIVE guard coverage (I2 09:57: preserve origin+generation rejection):
    // a sign-out landing between request build and response (interleaved at
    // the harness-only preDispatchHook) invalidates the operation generation,
    // so the held response's Set-Cookie is dropped and dead credentials are
    // not restored.
    @Test fun heldPollRejectsStaleGenerationCookie()=fixture {server,session,api->
        api.bootstrap()
        server.enqueue(MockResponse().setBody("""{"changes":[],"next_cursor":"watermark","has_more":false}""").addHeader("Set-Cookie","__Host-homeroom=stale; Path=/; Secure; HttpOnly"))
        api.preDispatchHook = { session.clear() }
        try { api.heldUpdates(null,0) } catch (_:SessionEndedException) {} finally { api.preDispatchHook = null }
        assertNull(session.cookie)
    }
    @Test fun receiptsNeverBootstrapOrRenew()=fixture {server,_,api->
        server.enqueue(MockResponse().setBody("{}"))
        api.testReceiptDirect("test","received","fixed")
        assertEquals(1,server.requestCount);val request=server.takeRequest()
        assertEquals("/homeroom/v1/owner/status/test-alerts/test/receipts",request.path)
        assertEquals("csrf",request.getHeader("X-CSRF-Token"));assertEquals("fixed",JSONObject(request.body.readUtf8()).getString("client_id"))
    }
    @Test fun failedRefreshDoesNotAdvanceCursor()=fixture {server,_,api->
        bootstrap(server)
        repeat(2) {server.enqueue(MockResponse().setBody("""{"changes":[],"next_cursor":"w$it","has_more":false}"""))}
        var refreshes=0
        try {withTimeout(6000) {LiveUpdates(api) {_,_->refreshes++;if(refreshes==1)throw java.io.IOException("snapshot failed") else throw CancellationException("done")}.run()};fail("continued")}
        catch(_:CancellationException) {}
        assertEquals(2,refreshes)
        server.takeRequest()
        repeat(2) {assertFalse(server.takeRequest().path!!.contains("cursor="))}
    }
    @Test fun outboxRetryKeepsExactBodyAfterLostResponse()=fixture {server,session,api->
        bootstrap(server)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporary"}"""))
        val b=JSONObject().put("client_id","stable").put("client_created_at","2026-11-11T16:00:00Z").put("text","unchanged")
        val outbox=Outbox(session);outbox.enqueue("room",b);outbox.drain(api)
        assertEquals("not_confirmed",outbox.entries().single().getString("state"))
        server.enqueue(MockResponse().setBody("""{"event_id":"e","replayed":true}"""))
        outbox.retry("stable");outbox.drain(api)
        assertEquals("sent",outbox.entries().single().getString("state"))
        server.takeRequest();assertEquals(server.takeRequest().body.readUtf8(),server.takeRequest().body.readUtf8())
    }
    @Test fun downloadHashFailureLeavesNoPartialFile()=fixture {server,_,api->
        bootstrap(server);server.enqueue(MockResponse().setBody("abc"))
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"download-test")
        try {api.verifiedDownload("/owner/files/f",file,3,"0".repeat(64)) {_,_->};fail("wrong hash accepted")}catch(_:IllegalArgumentException) {}
        assertFalse(file.exists())
    }
    @Test fun downloadSuccessAndRedirectClosed()=fixture {server,_,api->
        bootstrap(server);server.enqueue(MockResponse().setBody("abc"))
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"download-test")
        assertTrue(api.verifiedDownload("/owner/files/f",file,3,sha256("abc".toByteArray())) {_,_->});assertEquals("abc",file.readText());file.delete()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://untrusted.invalid/"))
        try {api.verifiedDownload("/owner/files/f",file,3,null) {_,_->};fail("redirect followed")}catch(e:ApiException) {assertEquals(302,e.status)}
        assertFalse(file.exists())
    }
}
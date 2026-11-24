package app.homeroom

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SetupHttpTest {
    private fun fixture(block:suspend (MockWebServer,ApiClient,Session)->Unit)=runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val session=Session(org.robolectric.RuntimeEnvironment.getApplication());session.clear();session.serverUrl=server.url("/").toString().trimEnd('/')
        val api=ApiClient(session) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(1,TimeUnit.SECONDS)}
        try {block(server,api,session)}finally {server.shutdown();session.clear()}
    }
    private fun ok()=MockResponse().setBody("{}")
    private fun session()=MockResponse().setBody("""{"csrf":"fixture","session_id":"fixture-session"}""")
    private fun enrolled()=ok().addHeader("Set-Cookie","__Host-homeroom=fixture; Path=/; Secure; HttpOnly")
    @Test fun successfulActualHttpsSequenceIsEnrollSessionRooms()=fixture {server,api,session ->
        server.enqueue(enrolled());server.enqueue(session());server.enqueue(MockResponse().setBody("""{"rooms":[]}"""))
        val c=SetupChecklist()
        c.connect(true,{accepted->api.enroll("one-use","fixture",accepted)},{fail("resume")},{api.roomRoster()},{})
        assertTrue(c.ready);assertTrue(session.enrolled)
        assertEquals("POST",server.takeRequest().method)
        assertEquals("/homeroom/v1/owner/session",server.takeRequest().path)
        assertEquals("/homeroom/v1/owner/rooms",server.takeRequest().path)
    }
    @Test fun lostBootstrapWithStoredCookieRetriesOnlySessionAndRooms()=fixture {server,api,session ->
        server.enqueue(enrolled());server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        val c=SetupChecklist()
        c.connect(true,{api.enroll("one-use","fixture",it)},{api.bootstrap()},{api.roomRoster()},{})
        assertFalse(c.ready);assertTrue(c.accepted);assertTrue(session.enrolled)
        assertEquals(2,server.requestCount)
        server.enqueue(session());server.enqueue(MockResponse().setBody("""{"rooms":[]}"""))
        c.connect(false,{fail("duplicate enrollment")},{api.bootstrap()},{api.roomRoster()},{})
        assertTrue(c.ready)
        val calls=(0 until 4).map {server.takeRequest()}
        assertEquals(1,calls.count {it.method=="POST"})
    }
    @Test fun lostEnrollmentResponseIsNotRepostedByChecklist()=fixture {server,api,_ ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val c=SetupChecklist()
        repeat(2) {c.connect(true,{api.enroll("one-use","fixture",it)},{api.bootstrap()},{api.roomRoster()},{})}
        assertFalse(c.ready);assertFalse(c.accepted);assertEquals(1,server.requestCount)
    }
    @Test fun room401RequestsNewLinkNotEndlessRoomRetry()=fixture {server,api,_ ->
        server.enqueue(enrolled());server.enqueue(session());server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"session_ended"}"""))
        val c=SetupChecklist()
        c.connect(true,{api.enroll("one-use","fixture",it)},{api.bootstrap()},{api.roomRoster()},{})
        assertTrue(c.ended);assertFalse(c.ready)
        assertEquals("Needs attention",c.checks[SetupStep.SIGN_IN]?.state)
    }
    @Test fun localSessionStorageKeepsConnectionAndDraftAlongsideNewMarker() {
        val app=org.robolectric.RuntimeEnvironment.getApplication();val before=Session(app);before.clear()
        before.serverUrl="https://fixture.example";before.cookie="fixture";before.saveComposerDraft("fixture","unsent fixture")
        before.lastSetupLinkHash="fixture-hash"
        val after=Session(app)
        assertTrue(after.enrolled);assertEquals("fixture",after.cookie);assertEquals("unsent fixture",after.composerDraft("fixture"));assertEquals("fixture-hash",after.lastSetupLinkHash)
        after.clear()
    }
}

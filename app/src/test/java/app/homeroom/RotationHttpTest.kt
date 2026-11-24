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
    private fun fixture(test:suspend kotlinx.coroutines.CoroutineScope.(MockWebServer,Session,()->ApiClient)->Unit)=runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.start()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val session=Session(org.robolectric.RuntimeEnvironment.getApplication());session.clear()
        session.serverUrl=server.url("/").toString().trimEnd('/');session.cookie="old-secret";session.csrf="old-csrf";session.sessionId="same-session"
        val client={ApiClient(session) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(2,TimeUnit.SECONDS)}}
        try {this.test(server,session,client)} finally {server.shutdown();session.clear()}
    }
    private fun bootstrap()=MockResponse().setBody("""{"csrf":"new-csrf","session_id":"same-session"}""").addHeader("Set-Cookie","__Host-homeroom=new-secret; Path=/; Secure; HttpOnly")
    @Test fun successfulSevenDayRotation()=fixture {server,session,client ->
        server.enqueue(bootstrap())
        server.enqueue(MockResponse().setBody("""{"features":{"session_renew":true}}"""))
        server.enqueue(MockResponse().setBody("""{"csrf":"renewed-csrf","session_id":"same-session"}"""))
        client().renewOnOpen()
        assertEquals("new-secret",session.cookie);assertEquals("renewed-csrf",session.csrf)
        assertEquals("/homeroom/v1/owner/session",server.takeRequest().path)
        val instance=server.takeRequest();assertTrue(instance.getHeader("Cookie")!!.contains("new-secret"))
        val renew=server.takeRequest();assertEquals("new-csrf",renew.getHeader("X-CSRF-Token"));assertEquals("POST",renew.method)
    }
    @Test fun lostRotationResponseDoesNotInventRecovery()=fixture {server,session,client ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"session_rotated"}"""))
        try {client().renewOnOpen();fail("response lost") } catch(_:Exception) {}
        assertEquals("old-secret",session.cookie);assertEquals("old-csrf",session.csrf)
        val prior=server.requestCount
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"session_rotated"}"""))
        try {client().eventDirect("e");fail("retired secret") } catch(e:ApiException) {assertEquals(409,e.status)}
        assertEquals(prior+1,server.requestCount)
    }
    @Test fun backgroundWaitsForForegroundRenewalAcrossClients()=fixture {server,session,client ->
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
    @Test fun revokedBackgroundSessionReturnsSessionEnded()=fixture {server,_,client ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"session_ended"}"""))
        try {client().eventDirect("e");fail("revoked") } catch(e:SessionEndedException) {assertEquals("session_ended",e.reason)}
        assertEquals(1,server.requestCount)
    }
}
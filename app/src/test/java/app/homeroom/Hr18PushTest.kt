package app.homeroom

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

// Push registration lifecycle against a real HTTPS MockWebServer (same pattern as SetupHttpTest).
// FirebaseMessaging is not initialised under Robolectric, so sync() takes its stored-token path.
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Hr18PushTest {
    private fun pending(v:Boolean) {PushTokenDeletion::class.java.getDeclaredField("pending").apply {isAccessible=true}.setBoolean(PushTokenDeletion,v)}
    private fun fixture(block:(MockWebServer,ApiClient,Session)->Unit) {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val session=Session(org.robolectric.RuntimeEnvironment.getApplication());session.clear();session.serverUrl=server.url("/").toString().trimEnd('/')
        val api=ApiClient(session) {it.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).readTimeout(1,TimeUnit.SECONDS)}
        pending(false)
        try {block(server,api,session)}finally {pending(false);server.shutdown();session.clear()}
    }
    private fun enroll(s:Session) {s.ownerId="owner";s.sessionId="phone";s.cookie="c";assertTrue(s.enrolled)}
    private fun scope()=CoroutineScope(Dispatchers.IO)

    // Token minted before enrollment, then enrollment completes: sync registers it (one POST, the stored token).
    @Test fun tokenStoredBeforeEnrollRegistersAfterEnroll()=fixture {server,api,session ->
        session.fcmToken="tok-pre-enroll"
        PushRegistration.sync(scope(),session,api)
        assertNull("not enrolled: nothing sent",server.takeRequest(400,TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("{}"));enroll(session)
        PushRegistration.sync(scope(),session,api)
        val r=server.takeRequest(5,TimeUnit.SECONDS)!!
        assertTrue(r.path!!.endsWith("/push/register"));assertTrue(r.body.readUtf8().contains("tok-pre-enroll"))
        assertNull("exactly one POST",server.takeRequest(400,TimeUnit.MILLISECONDS))
        assertEquals("tok-pre-enroll",session.fcmRegisteredToken)
    }

    // Cold start of an enrolled session registers the stored token without waiting for onNewToken.
    @Test fun coldStartRegistersStoredToken()=fixture {server,api,session ->
        enroll(session);session.fcmToken="tok-stored";server.enqueue(MockResponse().setBody("{}"))
        PushRegistration.sync(scope(),session,api)
        assertTrue(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8().contains("tok-stored"))
    }

    // Sign-out (session reset) between the call and the coroutine: nothing is sent and nothing stored.
    @Test fun staleGenerationSendsNothing()=fixture {server,api,session ->
        enroll(session);session.fcmToken="tok-old"
        val queue=ArrayList<Runnable>()
        val held=object:CoroutineDispatcher() {override fun dispatch(context:CoroutineContext,block:Runnable) {queue.add(block)}}
        PushRegistration.register(CoroutineScope(held),session,api)
        session.clear()
        queue.toList().forEach {it.run()}
        assertNull(server.takeRequest(400,TimeUnit.MILLISECONDS))
        assertNull(session.fcmRegisteredToken)
    }

    // A persisted deletion in progress blocks registration; once it completes, the next sync registers with no restart.
    @Test fun noRegisterWhileDeletionPendingThenRegistersAfter()=fixture {server,api,session ->
        enroll(session);session.fcmToken="tok-new";server.enqueue(MockResponse().setBody("{}"))
        pending(true)
        PushRegistration.sync(scope(),session,api)
        assertNull(server.takeRequest(400,TimeUnit.MILLISECONDS))
        pending(false)
        PushRegistration.sync(scope(),session,api)
        assertTrue(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8().contains("tok-new"))
    }

    // Sign-out deletion request persists (also when Firebase is unavailable) so the next start retries it.
    @Test fun signOutDeletionPersistsForRetry()=fixture {_,_,_ ->
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        PushTokenDeletion.request(app)
        assertTrue(app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).getBoolean("pending",false))
        pending(false)
        PushTokenDeletion.retryOnStart(app)
        assertTrue("pending reloaded from disk",PushTokenDeletion.pending)
        app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
}

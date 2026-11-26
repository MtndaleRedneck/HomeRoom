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
        pending(false);PushRegistration.tokenFetcher={done->done(null)} // deterministic: FCM unavailable unless a test says otherwise
        try {block(server,api,session)}finally {pending(false);PushRegistration.tokenFetcher=null;PushTokenDeletion.deleteTokenOp=null;PushTokenDeletion.onCompleted=null;server.shutdown();session.clear()}
    }
    private fun enroll(s:Session) {s.ownerId="ezzy";s.sessionId="phone";s.cookie="c";assertTrue(s.enrolled)}
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
        enroll(session) // enrolled again, so only the generation guard can stop the stale call
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

    // FCM issued a newer token than the stored one: exactly one POST, with the fresh token, which is also stored.
    @Test fun syncRegistersFreshTokenOnceNotTheStoredOne()=fixture {server,api,session ->
        enroll(session);session.fcmToken="tok-stored";server.enqueue(MockResponse().setBody("{}"))
        PushRegistration.tokenFetcher={done->done("tok-fresh")}
        PushRegistration.sync(scope(),session,api)
        assertTrue(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8().contains("tok-fresh"))
        assertNull("no second POST for the stored token",server.takeRequest(400,TimeUnit.MILLISECONDS))
        assertEquals("tok-fresh",session.fcmToken)
    }

    // FCM unavailable: the stored token still registers, once.
    @Test fun syncFallsBackToStoredTokenWhenFcmUnavailable()=fixture {server,api,session ->
        enroll(session);session.fcmToken="tok-stored";server.enqueue(MockResponse().setBody("{}"))
        PushRegistration.sync(scope(),session,api)
        assertTrue(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8().contains("tok-stored"))
        assertNull(server.takeRequest(400,TimeUnit.MILLISECONDS))
    }

    // A failed sign-out deletion is retried first. deleteToken is instance-wide, so on success EVERY cached token is dropped (also one that
    // arrived mid-deletion), completion is signalled, and the next sync registers only the freshly fetched token.
    @Test fun retryCompletesDropsAllCachedTokensThenRegistersFreshOnly()=fixture {server,api,session ->
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        enroll(session);session.fcmToken="tok-retired";session.fcmRegisteredToken="tok-retired"
        app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).edit().putBoolean("pending",true).commit()
        var completed=false
        PushTokenDeletion.onCompleted={completed=true}
        PushTokenDeletion.deleteTokenOp={done->
            session.fcmToken="tok-arrived-mid-deletion" // cannot be trusted: the deletion is instance-wide
            done(true)
        }
        PushTokenDeletion.retryOnStart(app)
        assertFalse(PushTokenDeletion.pending);assertTrue(completed)
        assertNull(session.fcmToken);assertNull(session.fcmRegisteredToken)
        assertFalse(app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).getBoolean("pending",false))
        server.enqueue(MockResponse().setBody("{}"))
        PushRegistration.tokenFetcher={done->done("tok-fresh")}
        PushRegistration.sync(scope(),session,api)
        assertTrue(server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8().contains("tok-fresh"))
        assertNull(server.takeRequest(400,TimeUnit.MILLISECONDS))
    }

    // An FCM fetch that began before a deletion started or finished is discarded when it returns (its token may be the deleted one).
    @Test fun staleInFlightFetchIsDiscardedAfterDeletion()=fixture {server,api,session ->
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        enroll(session);session.fcmToken="tok-stored"
        var held:((String?)->Unit)?=null
        PushRegistration.tokenFetcher={done->held=done}
        PushRegistration.sync(scope(),session,api)
        assertNotNull(held)
        PushTokenDeletion.deleteTokenOp={done->done(true)}
        PushTokenDeletion.request(app)           // a deletion starts and completes while the fetch is in flight
        held!!.invoke("tok-deleted-by-instance-wide-delete")
        assertNull("stale fetch result is not registered",server.takeRequest(400,TimeUnit.MILLISECONDS))
        app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    // A deletion that fails stays pending (and persisted) for the next retry.
    @Test fun failedDeletionStaysPending()=fixture {_,_,session ->
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        session.fcmToken="tok-x"
        PushTokenDeletion.deleteTokenOp={done->done(false)}
        PushTokenDeletion.request(app)
        assertTrue(PushTokenDeletion.pending)
        assertEquals("token untouched on failure","tok-x",session.fcmToken)
        assertTrue(app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).getBoolean("pending",false))
        app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Deletion succeeds after a re-enrol and FCM cannot be reached afterwards: the retired (deleted) token must not be registered.
    @Test fun neverRegistersTheRetiredTokenWhenFreshFetchFails()=fixture {server,api,session ->
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        enroll(session);session.fcmToken="tok-retired"
        app.getSharedPreferences("push_token_cleanup",android.content.Context.MODE_PRIVATE).edit().putBoolean("pending",true).commit()
        PushTokenDeletion.deleteTokenOp={done->done(true)}
        PushTokenDeletion.retryOnStart(app)
        assertNull("retired token dropped",session.fcmToken)
        PushRegistration.sync(scope(),session,api) // fetcher fake reports FCM unavailable
        assertNull("nothing registered",server.takeRequest(400,TimeUnit.MILLISECONDS))
    }
}

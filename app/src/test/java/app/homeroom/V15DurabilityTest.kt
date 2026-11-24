package app.homeroom
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class V15DurabilityTest {
    private fun body(id:String)=JSONObject().put("client_id",id).put("client_created_at","2026-11-11T15:00:00Z").put("text","hello").put("thread_key","thread").put("parent_id",JSONObject.NULL).put("mentions",JSONArray()).put("upload_ids",JSONArray())
    @Test fun enqueueBeforeSendAndBodyCannotChange() {
        val session=Session(RuntimeEnvironment.getApplication());session.clear()
        val queue=Outbox(session);val b=body("stable")
        queue.enqueue("room",b);queue.enqueue("room",b)
        assertEquals(1,Outbox(session).entries().size)
        assertEquals("queued",queue.entries().single().getString("state"))
        try {queue.enqueue("room",body("stable").put("text","changed"));fail("Changed retry accepted")}catch(_:IllegalArgumentException) {}
        assertEquals("hello",queue.entries().single().getJSONObject("body").getString("text"))
    }
    @Test fun interruptedSendingIsNeverAutomaticallyQueued() {
        val session=Session(RuntimeEnvironment.getApplication());session.clear();val queue=Outbox(session)
        queue.enqueue("room",body("same"))
        session.saveOutboxData(JSONArray(queue.entries().map {it.put("state","sending")}).toString())
        Outbox(session).recover()
        assertEquals("not_confirmed",queue.entries().single().getString("state"))
        queue.retry("same")
        assertEquals("queued",queue.entries().single().getString("state"));assertEquals("same",queue.entries().single().getJSONObject("body").getString("client_id"))
    }
    @Test fun deadGenerationCannotRestorePrivateQueue() {
        val session=Session(RuntimeEnvironment.getApplication());session.clear();val queue=Outbox(session)
        session.clear()
        try {queue.enqueue("room",body("old"));fail("old generation wrote")}catch(_:kotlinx.coroutines.CancellationException) {}
        assertNull(session.outboxData())
    }
    @Test fun stagedMetadataIsDurableAndClearsOnSignOut() {
        val session=Session(RuntimeEnvironment.getApplication());session.clear()
        session.saveStagedUpload("local",JSONObject().put("local_id","local").put("upload_client","one").put("post_client","two"))
        assertEquals("two",Session(RuntimeEnvironment.getApplication()).stagedUploads().single().getString("post_client"))
        session.clear();assertTrue(session.stagedUploads().isEmpty())
    }
}
package app.homeroom
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class V15ModelsTest {
    private val packet=JSONObject(javaClass.getResourceAsStream("/hr15-fixtures.json")!!.bufferedReader().readText())
    private fun body(name:String)=packet.getJSONArray("cases").objects().first {it.getString("name").startsWith(name)}.getJSONObject("response").getJSONObject("body")
    @Test fun releasedEventWrapperAndRevisions() {
        val event=ChatEvent.from(body("02").getJSONObject("event"))
        assertEquals(1,event.version);assertEquals(28L,event.attachments.single().size)
        assertEquals("verified",event.attachments.single().digestState)
        val deleted=ChatEvent.from(body("04").getJSONObject("event"))
        assertTrue(deleted.deleted);assertEquals("Message deleted",deleted.text);assertTrue(deleted.attachments.isEmpty())
    }
    @Test fun decisionAndUploadShapes() {
        val card=DecisionCard.from(body("24"))
        assertEquals(2,card.options.size);assertEquals("open",card.state)
        assertEquals(card.hash,body("25").getJSONObject("confirmation").getString("content_sha256"))
        val staged=UploadStatus.from(body("14"));val ready=UploadStatus.from(body("18"))
        assertEquals("staged",staged.state);assertNull(staged.verified)
        assertEquals("ready",ready.state);assertEquals(ready.expected,ready.verified)
    }
}
package app.homeroom

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ActionReviewTest {
    private fun vectors()=JSONArray(javaClass.getResourceAsStream("/hr15-action-vectors.json")!!.bufferedReader().readText()).objects()
    private fun vector(kind:String)=vectors().first {it.getJSONObject("action").getString("kind")==kind}
    private fun action(kind:String)=JSONObject(vector(kind).getJSONObject("action").toString())
    private fun rejects(o:JSONObject) {try {ActionReview.parse(o);fail("Accepted invalid action")}catch(_:IllegalArgumentException) {}catch(_:IllegalStateException) {}}
    @Test fun independentEmailAndPurchaseCanonicalVectorsMatch() {
        for(v in vectors()) {
            val review=ActionReview.parse(v.getJSONObject("action"))
            assertEquals(v.getString("action_canonical"),review.canonical)
            assertEquals(v.getString("action_sha256"),sha256(review.canonical.toByteArray(Charsets.UTF_8)))
            assertEquals(v.getString("content_canonical"),canonicalDecisionJson(v.getJSONObject("content")))
            assertEquals(v.getString("content_sha256"),sha256(canonicalDecisionJson(v.getJSONObject("content")).toByteArray(Charsets.UTF_8)))
        }
    }
    @Test fun paymentAndActorIdentitiesAreHashBound() {
        val original=action("purchase");val hash=sha256(ActionReview.parse(original).canonical.toByteArray())
        for(k in listOf("adapter_id","account_id","method_id","label")) {
            val changed=action("purchase");changed.getJSONObject("details").getJSONObject("payment_identity").put(k,"different")
            assertNotEquals(hash,sha256(ActionReview.parse(changed).canonical.toByteArray()))
        }
        val changed=action("purchase");changed.getJSONObject("authorized_actor").put("key_id","other_key")
        assertNotEquals(hash,sha256(ActionReview.parse(changed).canonical.toByteArray()))
    }
    @Test fun unknownOrMissingKeysFailAtEveryObject() {
        fun objects(o:JSONObject):List<JSONObject> = listOf(o)+o.keys().asSequence().flatMap {k->when(val x=o.get(k)) {is JSONObject->objects(x).asSequence();is JSONArray->(0 until x.length()).asSequence().flatMap {i->(x.get(i) as? JSONObject)?.let {objects(it).asSequence()} ?: emptySequence()};else->emptySequence()}}.toList()
        for(kind in listOf("email","purchase")) {
            val count=objects(action(kind)).size
            for(i in 0 until count) {
                val extra=action(kind);objects(extra)[i].put("unexpected","value");rejects(extra)
                val missing=action(kind);val target=objects(missing)[i];target.remove(target.keys().next());rejects(missing)
            }
        }
    }
    @Test fun unknownCurrencyKindAndPaymentFailClosed() {
        rejects(action("email").put("kind","sms"))
        rejects(action("purchase").apply {getJSONObject("details").put("currency","JPY")})
        rejects(action("purchase").apply {getJSONObject("details").getJSONObject("payment_identity").put("method_kind","bank_transfer")})
        rejects(action("purchase").apply {getJSONObject("details").getJSONObject("payment_identity").put("account_id","secret://value")})
    }
    @Test fun amountsNeverCoerceBooleanFloatOrText() {
        for(value in listOf(true,1.0,"100",JSONObject.NULL,-1L,1000000000001L)) {
            rejects(action("purchase").apply {getJSONObject("details").put("final_total_minor",value)})
        }
        assertEquals("$100.01 USD (10001 minor units)",ActionReview.usd(10001))
        assertEquals("$0.00 USD (0 minor units)",ActionReview.usd(0))
    }
    @Test fun allAudienceAndExactBodyAppearUncut() {
        val a=action("email");val d=a.getJSONObject("details")
        d.getJSONArray("cc").put(JSONObject().put("name","CC Person").put("address","CC@example.invalid"))
        d.getJSONArray("bcc").put(JSONObject().put("name","BCC Person").put("address","BCC@example.invalid"))
        val words="First line\n"+"x".repeat(19970)+"\nExact ending"
        d.getJSONObject("body").put("text",words)
        val fields=ActionReview.parse(a).fields
        assertTrue(fields.contains("Exact plain-text body" to words))
        assertTrue(fields.contains("CC exact address 1" to "CC@example.invalid"))
        assertTrue(fields.contains("BCC exact address 1" to "BCC@example.invalid"))
        d.getJSONObject("body").put("text","x".repeat(20001));rejects(a)
    }
    @Test fun controlsSurrogatesAndSecretShapedOriginsFail() {
        rejects(action("email").apply {getJSONObject("details").put("subject","bad\nsubject")})
        rejects(action("email").apply {getJSONObject("details").getJSONObject("body").put("text","bad\u0000body")})
        rejects(action("email").put("summary","bad\uD800"))
        for(origin in listOf("http://merchant.example.invalid","https://user:pass@merchant.example.invalid","https://merchant.example.invalid?token=secret","https://merchant.example.invalid/path")) {
            rejects(action("purchase").apply {getJSONObject("details").getJSONObject("merchant").put("origin",origin)})
        }
    }
    @Test fun deliveryAndUndoTermsMustBeCompleteAndUnknownCostHonest() {
        val original=action("purchase")
        assertTrue(ActionReview.parse(original).fields.contains("Undo cost" to "Unknown. Do not assume free cancellation."))
        rejects(action("purchase").apply {getJSONObject("details").getJSONObject("delivery").put("method","shipping")})
        rejects(action("purchase").apply {getJSONObject("details").getJSONObject("undo_terms").put("fee_minor",0)})
        rejects(action("purchase").apply {getJSONObject("details").getJSONObject("undo_terms").put("cancellable","false")})
    }
    @Test fun attachmentHashesAndTotalSizeRequiredNoSilentDrop() {
        val a=action("email");val attachments=a.getJSONObject("details").getJSONArray("attachments")
        attachments.put(JSONObject().put("file_id","file").put("name","exact.txt").put("byte_size",10).put("sha256","a".repeat(64)))
        assertTrue(ActionReview.parse(a).fields.contains("Attachment 1" to "exact.txt"))
        attachments.getJSONObject(0).put("sha256","A".repeat(64));rejects(a)
        attachments.getJSONObject(0).put("sha256","a".repeat(64)).put("byte_size",25L*1024*1024+1);rejects(a)
    }
    @Test fun immutableActionCardRequiresBothHashesAndCorrectEffect() {
        val v=vector("purchase");val raw=JSONObject(v.getJSONObject("content").toString()).put("decision_id","fixture").put("room_id","room").put("version",1).put("state","open").put("content_sha256",v.getString("content_sha256"))
        val card=DecisionCard.from(raw);assertNotNull(card.actionReviewOrNull());assertFalse(card.discussionConfirmable)
        assertNull(card.copy(hash="0".repeat(64)).actionReviewOrNull())
        assertNull(card.copy(actionHash="0".repeat(64)).actionReviewOrNull())
        assertNull(card.copy(effect="discussion").actionReviewOrNull())
    }
}

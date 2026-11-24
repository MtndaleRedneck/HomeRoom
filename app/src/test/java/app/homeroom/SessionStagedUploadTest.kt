package app.homeroom

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Session-level staged-upload store behavior (I2 migration constraints):
// local_id-keyed 2-arg save/remove, stable legacy normalization, and
// malformed-store write blocking that preserves the raw content.
@RunWith(RobolectricTestRunner::class)
class SessionStagedUploadTest {
    @org.junit.Before fun resetLatch() { Session.resetStagedStoreUnknownForTests() }
    private fun session():Session=Session(org.robolectric.RuntimeEnvironment.getApplication()).apply {clear()}

    @Test fun saveReplaceRemoveByLocalId() {
        val s=session()
        s.saveStagedUpload("a",JSONObject().put("local_id","a").put("room","r1"))
        s.saveStagedUpload("b",JSONObject().put("local_id","b").put("room","r1"))
        assertEquals(2,s.stagedUploads().size)
        s.saveStagedUpload("a",JSONObject().put("local_id","a").put("room","r1").put("size",7))
        assertEquals(7L,s.stagedUploads().single {it.optString("local_id")=="a"}.optLong("size"))
        s.saveStagedUpload("a",null)
        assertEquals(listOf("b"),s.stagedUploads().map {it.optString("local_id")})
    }

    @Test fun malformedStoreBlocksWritesAndRemovalPreservingRaw() {
        val s=session()
        val prefsField=Session::class.java.getDeclaredField("prefs").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val prefs=prefsField.get(s) as android.content.SharedPreferences
        prefs.edit().putString("staged_uploads","{not json").commit()
        assertTrue(s.stagedUploadsMalformed())
        assertEquals(emptyList<JSONObject>(),s.stagedUploads())
        try {s.saveStagedUpload("a",JSONObject().put("local_id","a"));fail("save on malformed")} catch(e:IllegalStateException) {}
        try {s.saveStagedUpload("a",null);fail("removal on malformed")} catch(e:IllegalStateException) {}
        assertEquals("{not json",prefs.getString("staged_uploads",null))
    }

    @Test fun legacyRecordsNormalizeWithStableIdentitiesAndSurviveRewrite() {
        val s=session()
        val prefsField=Session::class.java.getDeclaredField("prefs").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val prefs=prefsField.get(s) as android.content.SharedPreferences
        prefs.edit().putString("staged_uploads",org.json.JSONArray()
            .put(JSONObject().put("upload","up_1").put("name","one"))
            .put(JSONObject().put("name","mystery")).toString()).commit()
        val first=s.stagedUploads()
        assertEquals("up_1",first[0].optString("local_id"))
        assertTrue(first[1].optString("local_id").startsWith("quarantined-"))
        // Identities stable across reads; a canonical rewrite keeps ALL records.
        s.saveStagedUpload("up_1",JSONObject().put("local_id","up_1").put("name","one-v2"))
        val after=s.stagedUploads()
        assertEquals(2,after.size)
        assertEquals("one-v2",after.single {it.optString("local_id")=="up_1"}.optString("name"))
        assertTrue(after.any {it.optBoolean("quarantined")})
        assertEquals(first[1].optString("local_id"),after.single {it.optBoolean("quarantined")}.optString("local_id"))
    }

    @Test fun commitFailureLatchesUnknownAndBlocksLaterWritesProcessWide() {
        val s=session()
        s.saveStagedUpload("a",JSONObject().put("local_id","a"))
        // Fault injection: the next commit fails (memory may have advanced).
        s.stagedCommitHook={false}
        try {s.saveStagedUpload("a",JSONObject().put("local_id","a").put("size",3));fail("unconfirmed commit accepted")} catch(e:IllegalStateException) {}
        assertTrue(s.stagedUploadsUnknown())
        // Latch blocks ALL later writes, including removal, even after the
        // hook is cleared - a later save could persist the unknown state.
        s.stagedCommitHook=null
        try {s.saveStagedUpload("b",JSONObject().put("local_id","b"));fail("latched save went through")} catch(e:IllegalStateException) {}
        try {s.saveStagedUpload("a",null);fail("latched removal went through")} catch(e:IllegalStateException) {}
        // Reads keep working (the composer surfaces uncertainty, not a crash).
        s.stagedUploads()
    }
}

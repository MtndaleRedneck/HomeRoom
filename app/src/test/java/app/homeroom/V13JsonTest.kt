package app.homeroom

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class V13JsonTest {
    private val fp="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private fun keyJson(extra:String)=JSONObject("""{"key_id":"k","actor":"a","public_key_sha256":"$fp","status":"pending","rooms":["r"]$extra}""")
    @Test fun suffixFloorAndSuspectParsed() {
        val k=AiKey.from(keyJson(""","fingerprint_suffix_length":4,"suspect_pending":true"""))
        assertEquals(10,k.suffixLength);assertTrue(k.suspectPending)
        assertEquals("6789abcdef",k.confirmationSuffix)
    }
    @Test fun ruleScopeAisParsedOrNull() {
        val base="""{"pending_count":0,"rules":[]"""
        assertNull(RuleScope.from(JSONObject(base+"}")).ais)
        val s=RuleScope.from(JSONObject(base+""","ais":[{"actor":"t","display_name":"T","last_check_in_at":null,"last_rules_read_at":"2026-11-10T13:00:00Z"}]}"""))
        assertEquals(1,s.ais!!.size);assertNull(s.ais!![0].lastCheckInAt);assertEquals("2026-11-10T13:00:00Z",s.ais!![0].lastRulesReadAt)
    }
    @Test fun inviteParsedAndNeverStoresCodeElsewhere() {
        val i=JoinInvite.from(JSONObject("""{"join_id":"j","actor":"a","display_name":"n","expires_at":"2026-11-10T22:00:00Z","server_time":"2026-11-10T21:45:00Z","join_message":"m"}"""))
        assertEquals(900L,i.secondsLeft(0,0))
    }
    @Test fun avatarSampleSize() {
        assertEquals(1,Avatars.sampleSize(400,300))
        assertTrue(Avatars.sampleSize(4000,3000)>=4)
    }
}
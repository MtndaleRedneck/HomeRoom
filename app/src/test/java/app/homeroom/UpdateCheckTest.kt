package app.homeroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun newerTags() {
        assertTrue(UpdateCheck.isNewer("v1.1", "1.0"))
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0"))
        assertTrue(UpdateCheck.isNewer("2.0", "1.0"))
        assertTrue(UpdateCheck.isNewer("v1.0.1", "1.0"))
    }

    @Test
    fun notNewerTags() {
        assertFalse(UpdateCheck.isNewer("v1.0", "1.0"))
        assertFalse(UpdateCheck.isNewer("1.0", "1.0"))
        assertFalse(UpdateCheck.isNewer("v0.9", "1.0"))
    }

    @Test
    fun trustedDownloadUrls() {
        assertTrue(UpdateCheck.isTrustedDownloadUrl("https://github.com/MtndaleRedneck/HomeRoom/releases/latest/download/HomeRoom.apk"))
        assertTrue(UpdateCheck.isTrustedDownloadUrl("https://objects.githubusercontent.com/release-asset"))
        assertFalse(UpdateCheck.isTrustedDownloadUrl("http://github.com/MtndaleRedneck/HomeRoom/releases/latest/download/HomeRoom.apk"))
        assertFalse(UpdateCheck.isTrustedDownloadUrl("https://github.com.evil.example/HomeRoom.apk"))
        assertFalse(UpdateCheck.isTrustedDownloadUrl("https://evil.example/HomeRoom.apk"))
        assertFalse(UpdateCheck.isTrustedDownloadUrl("not a url"))
    }

    @Test
    fun digestParseAndMatch() {
        val hex="fb606c6b21db93eb3e900b0c642cf9b6b593161d27ae686d1c4e9630e71bf434"
        assertEquals(hex,UpdateCheck.parseDigest("sha256:"+hex))
        assertNull(UpdateCheck.parseDigest(hex.drop(2)))
        assertNull(UpdateCheck.parseDigest("sha256:xyz"))
        assertNull(UpdateCheck.parseDigest(null))
        assertTrue(UpdateCheck.digestMatches(hex,hex.uppercase()))
        assertFalse(UpdateCheck.digestMatches(hex,hex.replace('f','a')))
        assertFalse(UpdateCheck.digestMatches(null,hex))
    }
}

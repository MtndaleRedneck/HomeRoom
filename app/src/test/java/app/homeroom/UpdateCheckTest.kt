package app.homeroom

import org.junit.Assert.assertFalse
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
}

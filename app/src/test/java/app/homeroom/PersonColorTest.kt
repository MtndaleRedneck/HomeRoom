package app.homeroom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonColorTest {
    @Test fun namedActorsKeepTheirColors() {
        assertEquals(C.violet, personColor("instinct", null))
        assertEquals(C.violet, personColor("Instinct", null))
        assertEquals(C.teal, personColor("tab", null))
        assertEquals(C.accent, personColor("owner", null))
        assertEquals(C.muted, personColor("someone-else", null))
        assertEquals(C.muted, personColor(null, null))
    }
    @Test fun aiActorsStillHashIntoThePalette() {
        assertEquals(personColor("ai_one", "u-1"), personColor("ai_one", "u-1"))
        assertNotEquals(C.muted, personColor("ai_one", "u-1"))
    }
    @Test fun theSessionOwnersIdGetsTheOwnerAccentCaseInsensitively() {
        assertEquals(C.accent, personColor("u-1", "u-1"))
        assertEquals(C.accent, personColor("U-1", "u-1"))
        assertEquals(C.muted, personColor("u-2", "u-1"))
        assertEquals(C.muted, personColor("u-1", null))
        assertEquals(C.muted, personColor("u-1", ""))
    }
}

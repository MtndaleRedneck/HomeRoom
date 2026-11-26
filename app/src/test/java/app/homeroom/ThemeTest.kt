package app.homeroom

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Theme (Nov 25 owner request: dark by default): the palette is forced dark
// even when the phone is in a light appearance, until a light mode is
// qualified on real device pixels.
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    // C.light is process-global: every palette mutation must be undone so the
    // rest of the suite keeps its dark-default assumption.
    @org.junit.After fun restorePalette() {
        C.light = false
    }

    @Test fun forcesDarkUnderLightSystemFlag() {
        // Precondition pinned (I2 finite read, Nov 25): the palette must be
        // genuinely light BEFORE applyTo - deriving it from the ambient
        // Robolectric uiMode could silently start dark and pass vacuously.
        C.light = true
        assertTrue("precondition: light palette forced before applyTo", C.light)
        Theme.applyTo(app)
        assertFalse("applyTo must force dark even from a light palette", C.light)
    }

    @Test fun labelIsDark() {
        assertEquals("Dark", Theme.label(app))
    }
}

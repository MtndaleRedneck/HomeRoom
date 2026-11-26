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
        val systemLight = (app.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        C.light = systemLight
        Theme.applyTo(app)
        assertFalse(C.light)
    }

    @Test fun labelIsDark() {
        assertEquals("Dark", Theme.label(app))
    }
}

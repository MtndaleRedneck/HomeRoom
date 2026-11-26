package app.homeroom

import android.content.Context
import android.content.res.Configuration

/**
 * App theme choice (owner request Nov 25, 2026: "Dark mode by default").
 * Dark is the default when nothing is stored; Follow phone (system night-mode
 * flag) remains selectable in Settings. Only these two supported modes are
 * offered: an explicit Light choice is deliberately not exposed because parts
 * of the palette are dark-tuned. Kept in its own prefs file, separate from
 * the session store, so the choice applies before Session exists and survives
 * sign-out - it is a display preference, not account data.
 */
object Theme {
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREFS = "homeroom_theme"
    private const val KEY_THEME = "theme"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, DARK) ?: DARK

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply()
    }

    /** Applies the stored choice to the shared palette. Call before any view reads C. */
    fun applyTo(context: Context) {
        C.light = when (current(context)) {
            SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
            else -> false
        }
    }

    fun label(context: Context): String = when (current(context)) {
        SYSTEM -> "Follow phone"
        else -> "Dark"
    }
}

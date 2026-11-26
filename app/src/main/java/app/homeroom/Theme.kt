package app.homeroom

import android.content.Context

/**
 * App theme (owner request Nov 25, 2026: "dark mode by default").
 * Fixed dark: the released 1.4 behavior and the owner's standing taste.
 * A Follow-phone or Light choice is deliberately NOT offered yet: several
 * palette values are dark-tuned, so a light appearance must be qualified on
 * real device pixels before it is exposed. When that qualification lands,
 * this object becomes the single place a stored choice is read.
 */
object Theme {
    const val DARK = "dark"

    /** Applies the theme to the shared palette. Call before any view reads C. */
    fun applyTo(context: Context) {
        C.light = false
    }

    fun label(context: Context): String = "Dark"
}

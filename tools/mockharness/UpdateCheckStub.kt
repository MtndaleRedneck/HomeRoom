package app.homeroom

/** Harness stub exposing only what ApiClient uses (app source: UpdateCheck.kt). */
internal object UpdateCheck {
    fun parseDigest(d: String?): String? =
        d?.removePrefix("sha256:")?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
}

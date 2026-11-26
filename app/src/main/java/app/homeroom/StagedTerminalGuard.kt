package app.homeroom

// RECONSTRUCTED Nov 24, 2026: terminal/retirement boundary for staged uploads.
// I2 finding: visibility + cancel alone do NOT close the race - a delayed
// draft save can pass a terminal check, pause, and re-add a record AFTER the
// composer retired and removed it. The terminal check, the write, and the
// retirement must be ONE atomic boundary. This guard is that boundary, kept
// free of Android types so the race is exercisable in plain JVM tests.
internal class StagedTerminalGuard {
    private val lock = Any()

    @Volatile
    var terminal: Boolean = false
        private set

    /**
     * Sets terminal. Blocks until any in-flight persist completes, so the
     * caller's removal afterwards can never be overtaken by a late write.
     */
    fun retire() = synchronized(lock) { terminal = true }

    /**
     * Runs [block] under the same boundary as [retire]. Returns null when the
     * guard is already terminal (block NOT run); otherwise block's result.
     * Exceptions from block propagate (after releasing the lock).
     */
    fun <T> persist(block: () -> T): T? = synchronized(lock) {
        if (terminal) null else block()
    }
}

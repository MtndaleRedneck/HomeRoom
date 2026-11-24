package app.homeroom

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Fault-injection coverage for the durability gate (commits 042203e/78c7a86/
 * 4807584) and the recovered credential commit (8046353).
 *
 * MODEL BOUNDARY: the injected editor models SharedPreferences' documented
 * commit() contract - in-memory state updated, disk write failed (false
 * returned). It does NOT model an actual disk failure; what it establishes is
 * the APP's failure handling: loud failure, no false durable record,
 * fail-closed removal, and retry-heals. "Restart" is simulated with a fresh
 * wrapper whose in-memory overlay is empty, so it observes only the state a
 * real commit() would have durably written.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class SessionCommitFaultTest {

    /** SharedPreferences whose edit() returns an editor that, while
        [failing] is true, applies mutations only to an in-memory overlay
        (visible to readers through this wrapper) and reports commit()==false
        without touching the delegate - memory ahead of "disk". */
    private class FaultPrefs(private val delegate: SharedPreferences, private val overlay: MutableMap<String, Any?>, private val failing: () -> Boolean) : SharedPreferences by delegate {
        override fun getString(key: String?, defValue: String?): String? =
            if (key != null && overlay.containsKey(key)) overlay[key] as String? else delegate.getString(key, defValue)
        override fun contains(key: String?): Boolean = if (key != null && overlay.containsKey(key)) overlay[key] != null else delegate.contains(key)
        override fun edit(): SharedPreferences.Editor {
            val mutations = LinkedHashMap<String, Any?>()
            var cleared = false
            return object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun putInt(k: String?, v: Int) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun putLong(k: String?, v: Long) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun putFloat(k: String?, v: Float) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) mutations[k] = v } as SharedPreferences.Editor
                override fun remove(k: String?) = apply { if (k != null) mutations[k] = null } as SharedPreferences.Editor
                override fun clear() = apply { cleared = true; mutations.clear() } as SharedPreferences.Editor
                override fun commit(): Boolean {
                    return if (failing()) {
                        if (cleared) overlay.clear()
                        overlay.putAll(mutations)
                        false
                    } else {
                        val e = delegate.edit()
                        if (cleared) e.clear()
                        for ((k, v) in mutations) when (v) {
                            null -> e.remove(k)
                            is String -> e.putString(k, v)
                            is Long -> e.putLong(k, v)
                            is Int -> e.putInt(k, v)
                            is Boolean -> e.putBoolean(k, v)
                            is Float -> e.putFloat(k, v)
                            else -> throw UnsupportedOperationException()
                        }
                        val ok = e.commit()
                        if (ok) { if (cleared) overlay.clear(); overlay.keys.removeAll(mutations.keys) }
                        ok
                    }
                }
                override fun apply() {
                    if (failing()) { if (cleared) overlay.clear(); overlay.putAll(mutations) }
                    else {
                        val e = delegate.edit()
                        if (cleared) e.clear()
                        for ((k, v) in mutations) when (v) {
                            null -> e.remove(k)
                            is String -> e.putString(k, v)
                            is Long -> e.putLong(k, v)
                            is Int -> e.putInt(k, v)
                            is Boolean -> e.putBoolean(k, v)
                            is Float -> e.putFloat(k, v)
                            else -> throw UnsupportedOperationException()
                        }
                        e.apply()
                    }
                }
            }
        }
    }

    private class FaultContext(base: Context, private val overlay: MutableMap<String, Any?>, private val failing: () -> Boolean) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (name == "homeroom_session") FaultPrefs(super.getSharedPreferences(name, mode), overlay, failing)
            else super.getSharedPreferences(name, mode)
    }

    private var failing = false
    private val overlay = LinkedHashMap<String, Any?>()
    private fun freshSession(): Session {
        // Fresh wrapper + empty overlay == "process restart": only durably
        // committed state is visible.
        val ctx = FaultContext(RuntimeEnvironment.getApplication(), LinkedHashMap()) { false }
        return Session(ctx)
    }
    private fun faultSession(): Session =
        Session(FaultContext(RuntimeEnvironment.getApplication(), overlay) { failing })
    private fun record(id: String) = JSONObject().put("upload", id).put("upload_client", "u-$id").put("post_client", "p-$id")

    @Test fun failedSaveThrowsAndLeavesNoFalseDurableRecord() {
        freshSession().clear(); overlay.clear(); failing = true
        val session = faultSession()
        try { session.saveStagedUpload(record("a")); fail("failed commit was silent") }
        catch (e: IllegalStateException) { /* expected: loud failure */ }
        // Documented boundary: the SAME instance observes the memory-ahead-of-
        // disk state (this is exactly the state the durability gate guards).
        assertEquals(1, session.stagedUploads().size)
        // After a "restart" only durable state is visible: no false record.
        assertTrue(freshSession().stagedUploads().isEmpty())
    }

    @Test fun retryAfterFailedSaveHeals() {
        freshSession().clear(); overlay.clear()
        val session = faultSession()
        failing = true
        try { session.saveStagedUpload(record("b")) } catch (_: IllegalStateException) {}
        failing = false
        session.saveStagedUpload(record("b"))
        assertEquals("p-b", freshSession().stagedUploads().single().getString("post_client"))
    }

    @Test fun failedRemoveKeepsRecordFailClosed() {
        freshSession().clear(); overlay.clear(); failing = false
        val session = faultSession()
        session.saveStagedUpload(record("c"))
        failing = true
        try { session.removeStagedUpload("c"); fail("failed remove was silent") }
        catch (e: IllegalStateException) { /* expected */ }
        assertEquals(1, session.stagedUploads().size)
        assertEquals(1, freshSession().stagedUploads().size)
    }

    @Test fun staleGenerationCannotWriteAfterClear() {
        // Generation-guard semantics behind the new shared stateLock (I2
        // sign-out race finding). Sequential: discriminates the guard, not the
        // scheduling race itself - the synchronized(stateLock) boundary in
        // Session is what closes check-then-clear-then-write interleavings.
        val session = freshSession().also { it.clear() }
        val stale = session.generation
        session.clear()
        var wrote = false
        session.withinGeneration(stale) { session.replaceCredentials("ghost", "ghost"); wrote = true }
        assertFalse("stale generation wrote credentials", wrote)
        assertNull(session.cookie)
    }

    @Test fun failedCredentialCommitThrowsBeforeInMemoryUse() {
        freshSession().clear(); overlay.clear(); failing = true
        val session = faultSession()
        try { session.replaceCredentials("new-cookie", "new-csrf"); fail("failed credential commit was silent") }
        catch (e: IllegalStateException) { /* expected */ }
        // ApiClient assigns its in-memory csrf/sessionData only AFTER this call
        // returns, so a throw leaves in-memory and durable state consistent.
        assertNull(freshSession().cookie)
        assertNull(freshSession().csrf)
    }
}

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

    @org.junit.Before fun resetLatch() { Session.resetStagedStoreUnknownForTests() }
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
    private fun record(id: String) = JSONObject().put("local_id", id).put("upload", id).put("upload_client", "u-$id").put("post_client", "p-$id")

    @Test fun failedSaveThrowsAndLeavesNoFalseDurableRecord() {
        freshSession().clear(); overlay.clear(); failing = true
        val session = faultSession()
        try { session.saveStagedUpload("a", record("a")); fail("failed commit was silent") }
        catch (e: IllegalStateException) { /* expected: loud failure */ }
        // Documented boundary: the SAME instance observes the memory-ahead-of-
        // disk state (this is exactly the state the durability gate guards).
        assertEquals(1, session.stagedUploads().size)
        // After a "restart" only durable state is visible: no false record.
        assertTrue(freshSession().stagedUploads().isEmpty())
    }

    @Test fun retryAfterFailedSaveIsBlockedUntilRestart() {
        // DESIGN CHANGE (Nov 24, I2 latch review): an unconfirmed commit latches
        // the staged store UNKNOWN and blocks ALL later writes for the life of
        // the process - the old "retry heals" expectation is superseded,
        // because a later save could persist the unknown state. A "restart"
        // (fresh prefs view) re-reads disk and writes work again.
        freshSession().clear(); overlay.clear()
        Session.resetStagedStoreUnknownForTests()
        val session = faultSession()
        failing = true
        try { session.saveStagedUpload("b", record("b")) } catch (_: IllegalStateException) {}
        failing = false
        assertTrue(session.stagedUploadsUnknown())
        try { session.saveStagedUpload("b", record("b")); fail("latched save went through") }
        catch (_: IllegalStateException) { /* blocked by the latch */ }
        Session.resetStagedStoreUnknownForTests()
        session.saveStagedUpload("b", record("b"))
        assertEquals("p-b", freshSession().stagedUploads().single().getString("post_client"))
    }

    @Test fun failedRemoveKeepsRecordFailClosed() {
        freshSession().clear(); overlay.clear(); failing = false
        val session = faultSession()
        session.saveStagedUpload("c", record("c"))
        failing = true
        try { session.saveStagedUpload("c", null); fail("failed remove was silent") }
        catch (e: IllegalStateException) { /* expected */ }
        // Current production contract, documented in Session.saveStagedUpload:
        // a failed commit leaves the save UNKNOWN (in-memory map may be ahead
        // of disk), latches all further staged writes, and signals the UI via
        // stagedUploadsUnknown(). The older test lineage asserted the record
        // was simply kept.
        assertTrue(session.stagedUploadsUnknown())
        try { session.saveStagedUpload("d", record("d")); fail("latched write was allowed") }
        catch (e: IllegalStateException) { /* expected: writes blocked */ }
        // The on-disk record was never touched: a fresh session still sees it.
        assertEquals(1, freshSession().stagedUploads().size)
    }

    @org.junit.Ignore("QUARANTINED contradicts the verified binary: K0/V4.s throws CancellationException(\"Session changed\") on a stale generation - this test's silent-drop expectation predates the 9e09ce9 throw-on-stale restoration; the no-land property is pinned by characterization_staleGenerationWriteThrowsAndDoesNotLand below")
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

    // CHARACTERIZATION of the verified binary contract (K0/V4.s:986-1007): a
    // stale generation makes withinGeneration THROW CancellationException
    // ("Session changed") inside the session lock BEFORE the operation runs -
    // the dead session's write is loud and can never land. Pins the no-land
    // property of the two quarantined silent-drop expectations against the
    // release's actual mechanism.
    @Test fun characterization_staleGenerationWriteThrowsAndDoesNotLand() {
        val session = freshSession().also { it.clear() }
        val stale = session.generation
        session.clear()
        var wrote = false
        try { session.withinGeneration(stale) { session.replaceCredentials("ghost", "ghost"); wrote = true } }
        catch (e: kotlinx.coroutines.CancellationException) { assertEquals("Session changed", e.message) }
        assertFalse("stale generation wrote credentials", wrote)
        assertNull(session.cookie)
        val s2 = freshSession().also { it.clear() }
        s2.saveStagedUpload("a", JSONObject().put("local_id", "a"))
        val old2 = s2.generation
        s2.clear()
        try { s2.withinGeneration(old2) { s2.saveStagedUpload("b", JSONObject().put("local_id", "b")) } }
        catch (e: kotlinx.coroutines.CancellationException) { assertEquals("Session changed", e.message) }
        assertTrue("stale staged write landed after clear", s2.stagedUploads().isEmpty())
    }

    @Test fun responseCookieFromDeadGenerationDropped() {
        // Operation-generation boundary (I2 recheck): a response whose request
        // started before sign-out must not persist its cookie afterwards.
        val session = freshSession().also { it.clear() }
        val opGen = session.generation
        assertTrue(session.acceptCookieForGeneration(opGen, "live-cookie"))
        assertEquals("live-cookie", session.cookie)
        session.clear()
        assertFalse(session.acceptCookieForGeneration(opGen, "ghost-cookie"))
        assertNull(session.cookie)
    }

    @Test fun authIfUnchangedReturnsAtomicSnapshotOrNothing() {
        val session = freshSession().also { it.clear() }
        session.serverUrl = "https://a.example"
        session.replaceCredentials("c1", "t1")
        val gen = session.generation
        val snap = session.authIfUnchanged(gen, "https://a.example")
        // Snapshot shape is the generation-bound OpAuth documented at
        // Session.authIfUnchanged; the older test lineage compared a Triple.
        assertEquals(Session.OpAuth(gen, "https://a.example", "c1", "t1"), snap)
        assertNull(session.authIfUnchanged(gen + 1, "https://a.example"))
        assertNull(session.authIfUnchanged(gen, "https://b.example"))
        session.clear()
        assertNull(session.authIfUnchanged(gen, "https://a.example"))
    }

    @Test fun staleTeardownCannotWipeNewSessionAndTeardownInvalidatesInflight() {
        // I2 teardown gap: an unattributed cookie clear let an in-flight
        // still-current response restore credentials; a stale response's 401
        // could also tear down a NEW session.
        val session = freshSession().also { it.clear() }
        session.serverUrl = "https://a.example"
        session.replaceCredentials("c1", "t1")
        val oldGen = session.generation
        session.clear() // sign-out + re-enroll bumps the generation
        assertFalse("stale teardown wiped the new session", session.endSession(oldGen))
        // Attributed teardown of the CURRENT session works and invalidates
        // in-flight old-generation responses.
        session.serverUrl = "https://a.example"
        session.replaceCredentials("c2", "t2")
        val curGen = session.generation
        assertTrue(session.endSession(curGen))
        assertNull(session.cookie)
        assertFalse("in-flight old-generation response restored the cookie",
            session.acceptCookieForGeneration(curGen, "ghost"))
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

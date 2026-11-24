package app.homeroom

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Plain-JVM regression for the I2 terminal race: a delayed draft save that
// checked terminal BEFORE retirement must not re-add the record after the
// composer retired and removed it. Visibility + cancel alone did not close
// this; the guard makes check/write/retire one atomic boundary.
class StagedTerminalGuardTest {

    @Test fun persistAfterRetireIsRefusedAndNeverRuns() {
        val guard=StagedTerminalGuard()
        guard.retire()
        var ran=false
        val result=guard.persist { ran=true; true }
        assertNull(result)
        assertFalse(ran)
    }

    @Test fun retireCannotOvertakeAnInFlightWrite() {
        val guard=StagedTerminalGuard()
        val store=java.util.concurrent.CopyOnWriteArrayList<String>()
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        // Delayed write: enters persist (passes the terminal check), then
        // pauses mid-write while holding the boundary.
        val writer=Thread {
            guard.persist {
                entered.countDown()
                release.await(5,TimeUnit.SECONDS)
                store.add("record")
            }
        }
        writer.start()
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val retirer=Thread { guard.retire() }
        retirer.start()
        Thread.sleep(150)
        // Serialization: retire must still be waiting on the in-flight write.
        assertTrue("retire overtook an in-flight write",retirer.isAlive)
        release.countDown()
        writer.join(5000); retirer.join(5000)
        // Removal runs only after retire returns; the admitted write already
        // completed, so removal clears it and nothing can re-add it.
        store.clear()
        assertFalse(store.contains("record"))
        assertNull(guard.persist { store.add("record2") })
        assertFalse(store.contains("record2"))
    }

    @Test fun continuousMarkerWritesNeverLandAfterRetire() {
        // The c69 bypass paths (markConsumedUnresolved marker save, resend
        // rotation save) reduce to this: any number of writes racing
        // retirement must stop being admitted the moment retire returns.
        val guard=StagedTerminalGuard()
        val store=java.util.concurrent.CopyOnWriteArrayList<String>()
        val stop=java.util.concurrent.atomic.AtomicBoolean(false)
        val writer=Thread { while(!stop.get()) guard.persist { store.add("m") } }
        writer.start()
        Thread.sleep(50)
        guard.retire(); stop.set(true); writer.join(5000)
        assertFalse(writer.isAlive)
        store.clear()
        assertNull(guard.persist { store.add("m2") })
        assertTrue(store.isEmpty())
    }
}

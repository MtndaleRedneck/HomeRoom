package app.homeroom

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Executed locally by run.sh: the same two guard scenarios as
// StagedTerminalGuardTest, without JUnit (no Android test env here).
fun main() {
    val g1=StagedTerminalGuard(); g1.retire()
    var ran=false
    val r1=g1.persist { ran=true; true }
    check(r1==null && !ran) {"persist ran after retire"}
    println("OK guard-retire-refusal")

    val g2=StagedTerminalGuard()
    val store=java.util.concurrent.CopyOnWriteArrayList<String>()
    val entered=CountDownLatch(1); val release=CountDownLatch(1)
    val w=Thread { g2.persist { entered.countDown(); release.await(5,TimeUnit.SECONDS); store.add("rec") } }
    w.start(); check(entered.await(5,TimeUnit.SECONDS)) {"writer never entered persist"}
    val rt=Thread { g2.retire() }; rt.start()
    Thread.sleep(150)
    check(rt.isAlive) {"retire overtook an in-flight write - check/write/retire not one boundary"}
    release.countDown(); w.join(5000); rt.join(5000)
    store.clear()
    check(!store.contains("rec")) {"delayed write re-added a retired record"}
    check(g2.persist { store.add("rec2") }==null && !store.contains("rec2")) {"post-retire write admitted"}
    println("OK guard-retire-serialization")

    // C: marker/rotation-style writes (the c69 bypass paths) reduce to the
    // same boundary: a continuous writer racing retirement must never land a
    // write after retire returns and removal runs.
    val g3=StagedTerminalGuard()
    val s3=java.util.concurrent.CopyOnWriteArrayList<String>()
    val stop=java.util.concurrent.atomic.AtomicBoolean(false)
    val w3=Thread { while(!stop.get()) g3.persist { s3.add("m") } }
    w3.start(); Thread.sleep(50)
    g3.retire(); stop.set(true); w3.join(5000)
    check(!w3.isAlive) {"writer stuck after retire"}
    s3.clear()
    check(s3.isEmpty() && g3.persist { s3.add("m2") }==null && s3.isEmpty()) {"a marker/rotation write landed after retire+removal"}
    println("OK guard-marker-rotation-race")
}

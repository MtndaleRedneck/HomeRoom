package app.homeroom

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Room-level unresolved-ask retention (I2 379008b + f409710 reviews).
 *
 * An ask-all POST whose acceptance is unknown must keep blocking a NEW send
 * even after the RoomScreen instance is destroyed and recreated: leaving and
 * re-entering the room must not lose the marker. Entries are keyed by session
 * generation + room id, so a moved identity never reconciles against, or is
 * blocked by, another session's ask.
 *
 * Accepted asks retain ONLY (requestId, generation, serverUrl) - never
 * credentials (I2 f409710 #5): recovery re-verifies the identity against the
 * current session and uses a FRESH auth snapshot.
 *
 * PROCESS-LIFETIME ONLY (explicit policy, not full persistence): process
 * death drops the guard. The private12 release has no such guard at all, so
 * this is strictly stronger than parity; after a restart the accepted ask
 * remains discoverable in the room's own event history. No server-side
 * duplicate suppression is claimed or assumed.
 */
class AskAllPendingStore {
    data class Accepted(val requestId: String, val generation: Long, val serverUrl: String?)

    private val pending = ConcurrentHashMap<String, String>()                         // key -> clientId
    private val accepted = ConcurrentHashMap<String, ConcurrentLinkedQueue<Accepted>>() // key -> FIFO queue

    private fun key(generation: Long, roomId: String) = "$generation:$roomId"

    /**
     * Atomic room claim, made BEFORE dispatch (GPT/Gem f409 #3): two
     * overlapping flows (a second RoomScreen instance, or two dialogs racing
     * a roster await) can both pass the beginAskAll check; only the first
     * claim wins. Re-claiming the SAME client_id is idempotent - the
     * release's in-dialog retry resends the identical body. An in-flight ask
     * is as unresolved as a failed one, so the claim precedes the POST.
     */
    fun claim(generation: Long, roomId: String, clientId: String): Boolean {
        val existing = pending.putIfAbsent(key(generation, roomId), clientId)
        return existing == null || existing == clientId
    }

    fun pendingClientId(generation: Long, roomId: String): String? = pending[key(generation, roomId)]

    /**
     * Acceptance reconciliation: clears ONLY when an observed client_id
     * matches the marker. An empty or unrelated fetch reconciles nothing,
     * including one that started before the POST: it can only contain this
     * client_id if the server actually accepted the ask.
     *
     * PROVENANCE (I2 f409710 #6): event client_id is the client-side model
     * field recovered from the release binary; whether the server echoes
     * client_id on ask-all-created events is NOT verified against a live
     * server (operator contract question). Reconcile is therefore best-effort;
     * the release's own recovery is the check-room flow, unchanged.
     */
    fun reconcile(generation: Long, roomId: String, seenClientIds: Collection<String?>): Boolean {
        val k = key(generation, roomId)
        val p = pending[k] ?: return false
        return if (seenClientIds.any { it == p }) pending.remove(k, p) else false
    }

    /**
     * Compare-remove (I2 f409710 #1): clears the marker ONLY for the ask it
     * names, so a late completion or override for an older ask can never
     * erase the marker of a newer one.
     */
    fun clearIfMatches(generation: Long, roomId: String, clientId: String): Boolean =
        pending.remove(key(generation, roomId), clientId)

    /** Queue (I2 f409710 #2): several accepted asks never overwrite each other. */
    fun retainAccepted(generation: Long, roomId: String, requestId: String, serverUrl: String?) {
        accepted.getOrPut(key(generation, roomId)) { ConcurrentLinkedQueue() }
            .add(Accepted(requestId, generation, serverUrl))
    }

    /**
     * Non-consuming read (I2 f409710 #3): the caller consumes via
     * dropAccepted ONLY after the answers page actually opened, so a failed
     * navigation never loses the request_id. Identity re-verified here: a
     * moved destination never recovers another scope's ask.
     */
    fun peekAccepted(generation: Long, roomId: String, serverUrl: String?): Accepted? {
        val a = accepted[key(generation, roomId)]?.peek() ?: return null
        return if (a.generation == generation && a.serverUrl == serverUrl) a else null
    }

    fun dropAccepted(generation: Long, roomId: String, requestId: String): Boolean {
        val q = accepted[key(generation, roomId)] ?: return false
        return q.removeIf { it.requestId == requestId }
    }
}

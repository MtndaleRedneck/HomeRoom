package app.homeroom

import java.util.concurrent.ConcurrentHashMap

/**
 * Room-level unresolved-ask retention (I2 379008b review, main 18:50).
 *
 * An ask-all POST whose acceptance is unknown must keep blocking a NEW send
 * even after the RoomScreen instance is destroyed and recreated: leaving and
 * re-entering the room must not lose the marker. Entries are keyed by session
 * generation + room id, so a moved identity never reconciles against, or is
 * blocked by, another session's ask.
 *
 * PROCESS-LIFETIME ONLY (explicit policy, not full persistence): process
 * death drops the guard. The private12 release has no such guard at all, so
 * this is strictly stronger than parity; after a restart the accepted ask
 * remains discoverable in the room's own event history. No server-side
 * duplicate suppression is claimed or assumed.
 */
class AskAllPendingStore {
    data class Accepted(val requestId: String, val auth: Session.OpAuth)

    private val pending = ConcurrentHashMap<String, String>()      // key -> clientId
    private val accepted = ConcurrentHashMap<String, Accepted>()   // key -> accepted ask

    private fun key(generation: Long, roomId: String) = "$generation:$roomId"

    /** Set BEFORE dispatch: an in-flight ask is as unresolved as a failed one. */
    fun mark(generation: Long, roomId: String, clientId: String) {
        pending[key(generation, roomId)] = clientId
    }

    fun pendingClientId(generation: Long, roomId: String): String? = pending[key(generation, roomId)]

    /**
     * Acceptance reconciliation: clears ONLY when an observed client_id
     * matches the marker. An empty or unrelated fetch reconciles nothing,
     * including one that started before the POST: it can only contain this
     * client_id if the server actually accepted the ask.
     */
    fun reconcile(generation: Long, roomId: String, seenClientIds: Collection<String?>): Boolean {
        val k = key(generation, roomId)
        val p = pending[k] ?: return false
        return if (seenClientIds.any { it == p }) pending.remove(k, p) else false
    }

    /** Explicit resolution: POST accepted (request_id), or explicit user resend. */
    fun clear(generation: Long, roomId: String) { pending.remove(key(generation, roomId)) }

    /** Accepted ask retained until its answers page is actually opened. */
    fun retainAccepted(generation: Long, roomId: String, requestId: String, auth: Session.OpAuth) {
        accepted[key(generation, roomId)] = Accepted(requestId, auth)
    }

    /** One-shot recovery: returns the retained ask exactly once. */
    fun takeAccepted(generation: Long, roomId: String): Accepted? = accepted.remove(key(generation, roomId))
}

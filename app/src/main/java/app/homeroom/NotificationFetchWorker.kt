package app.homeroom

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * Second stage of a push: the generic notification is already showing. This
 * worker fetches the actual message with the stored session and replaces
 * that notification with sender and preview. It only ever calls the
 * single-event route the server added for background fetches - no room or
 * history routes, so a background wake never extends the idle timer.
 * Transient failures (offline, 5xx) retry with WorkManager backoff;
 * terminal ones (dead session, event gone) leave the generic notification.
 */
class NotificationFetchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.success()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.success()
        val session = Session(applicationContext)
        if (!session.enrolled) return Result.success()
        // Work queued under another session must not run with this session's credentials.
        if (staleSession(inputData.getString(KEY_SESSION_ID), session.sessionId)) return Result.success()
        val startSessionId = session.sessionId
        val prefs = applicationContext.getSharedPreferences(
            HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val seqKey = "last_seq_" + roomId
        return try {
            val api = ApiClient(session)
            val event = ChatEvent.from(
                api.event(eventId).optJSONObject("event") ?: return Result.success()
            )
            val sender = resolveSender(session, roomId, event)

            val notifId = HomeRoomMessagingService.notificationId(roomId)
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(HomeRoomMessagingService.EXTRA_ROOM_ID, roomId)
            }
            val pending = PendingIntent.getActivity(
                applicationContext, notifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val preview = if (event.text.length > 120) event.text.take(120) + "..." else event.text
            val publicVersion = NotificationCompat.Builder(applicationContext, HomeRoomMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chats)
                .setContentTitle("HomeRoom")
                .setContentText("New message")
                .build()
            val notification = NotificationCompat.Builder(applicationContext, HomeRoomMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chats)
                .setContentTitle(sender)
                .setContentText(preview)
                .setVisibility(PrivacyPrefs.visibility(applicationContext))
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            // Per-room lock: the seq read, the notify, and the seq write are
            // one atomic unit, so concurrent workers for the same room can
            // never interleave into an out-of-order result.
            val lock = roomLocks.getOrPut(roomId) { Any() }
            synchronized(lock) {
                val lastSeq = prefs.getLong(seqKey, -1)
                // Strictly older than the preview already showing: skip. The
                // generic notification this push posted is content-free and
                // still accurate, and an older preview must never replace a
                // newer one. Equal or newer seq posts (re)covering the
                // generic this push just showed.
                if (event.seq < lastSeq) return Result.success()

                // A sign-out - or sign-out then re-enroll - during the fetch
                // must not surface a stale preview: the session identity must
                // be exactly the one this fetch started with.
                val now = Session(applicationContext)
                if (!now.enrolled || now.sessionId != startSessionId) return Result.success()

                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, notification)
                prefs.edit().putLong(seqKey, maxOf(lastSeq, event.seq)).apply()
            }
            Result.success()
        } catch (e: SessionEndedException) {
            // Dead session: terminal, the generic notification stays.
            Result.success()
        } catch (e: ApiException) {
            // 401/404 are terminal; rate limits and server errors retry.
            if (e.status == 401 || e.status == 404) Result.success() else Result.retry()
        } catch (e: Exception) {
            // Offline, DNS, timeouts: retry with backoff; generic stays until then.
            Result.retry()
        }
    }

    private fun resolveSender(session: Session, roomId: String, event: ChatEvent): String {
        if (event.actorId == session.ownerId) return session.ownerName ?: event.actorName
        // The single-event route returns only a canonical actor ID. Map it
        // through the roster the app cached on its last foreground rooms
        // fetch; never call /owner/rooms from the background here.
        if (event.actorName != event.actorId) return event.actorName
        val cached = session.roomsCache ?: return event.actorName
        return try {
            val rooms = org.json.JSONArray(cached)
            var name: String? = null
            for (i in 0 until rooms.length()) {
                val room = Room.from(rooms.getJSONObject(i))
                if (room.id == roomId) {
                    name = room.members.firstOrNull { it.first == event.actorId }
                        ?.second?.ifBlank { null }
                    break
                }
            }
            name ?: event.actorName
        } catch (e: Exception) {
            event.actorName
        }
    }

    companion object {
        const val KEY_ROOM_ID = "room_id"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_SESSION_ID = "session_id"
        /** True when the work was queued under a session other than the current one (a missing queued id is an older enqueue: allowed). */
        internal fun staleSession(queued: String?, current: String?): Boolean = queued != null && queued != current
        const val KEY_ENQUEUED_AT = "enqueued_at"
        private val roomLocks = ConcurrentHashMap<String, Any>()
    }
}
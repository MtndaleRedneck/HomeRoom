package app.homeroom

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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

        // Retry bounds: give up after a few attempts or when the push is
        // stale - by then the generic notification is the right end state.
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.success()
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, 0L)
        if (enqueuedAt > 0 && System.currentTimeMillis() - enqueuedAt > MAX_AGE_MS) {
            return Result.success()
        }

        // Session identity is fixed at enqueue: sign-out or re-enroll since
        // then means this work belongs to a dead session.
        val startSessionId = inputData.getString(KEY_SESSION_ID)
        val session = Session(applicationContext)
        if (!session.enrolled || session.sessionId != startSessionId) {
            return Result.success()
        }
        val prefs = applicationContext.getSharedPreferences(
            HomeRoomMessagingService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val seqKey = "last_seq_" + roomId
        return try {
            val api = ApiClient(session)
            // eventDirect hits ONLY GET /owner/events/{id}: no bootstrap, so
            // a background wake never extends the session idle timer and
            // never rotates the cookie via /owner/session.
            val eventJson = api.eventDirect(eventId).optJSONObject("event") ?: return Result.success()
            if (eventJson.optString("room_id") != roomId) return Result.success()
            val event = ChatEvent.from(eventJson)
            val sender = resolveSender(session, roomId, event)
            val roomName = resolveRoomName(session, roomId)

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = android.net.Uri.parse("homeroom://room/" + android.net.Uri.encode(roomId))
                putExtra(HomeRoomMessagingService.EXTRA_ROOM_ID, roomId)
            }
            val pending = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val preview = if (event.text.length > 120) event.text.take(120) + "..." else event.text
            val ts = try {
                java.time.Instant.parse(event.createdAt).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
            val style = NotificationCompat.MessagingStyle(
                androidx.core.app.Person.Builder().setName(session.ownerName ?: "You").build()
            )
            if (roomName != null) {
                style.conversationTitle = roomName
                style.isGroupConversation = true
            }
            val publicVersion = NotificationCompat.Builder(applicationContext, HomeRoomMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("HomeRoom")
                .setContentText("New message")
                .build()
            val notification = NotificationCompat.Builder(applicationContext, HomeRoomMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(sender)
                .setContentText(preview)
                .apply { if (roomName != null) setSubText(roomName) }
                .setGroup(HomeRoomMessagingService.GROUP_KEY)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pending)

            // Per-room lock shared with the messaging service: the generic
            // post and this preview post can never interleave, and the seq
            // read + notify + seq write are one atomic unit.
            val lock = HomeRoomMessagingService.roomLocks.getOrPut(roomId) { Any() }
            synchronized(lock) {
                // Session identity must still hold before ANY write (a
                // sign-out mid-fetch must not write into the cleared store).
                val midCheck = Session(applicationContext)
                if (!midCheck.enrolled || midCheck.sessionId != startSessionId) return Result.success()

                val lastSeq = prefs.getLong(seqKey, -1)
                // Strictly older than the preview already showing: skip. The
                // generic notification this push posted is content-free and
                // still accurate, and an older preview must never replace a
                // newer one. Equal or newer seq posts (re)covering the
                // generic this push just showed.
                if (event.seq < lastSeq) {
                    // Too old to notify, but keep the stack complete: the
                    // store sorts by seq, so append without alerting.
                    PushMessageStore.append(
                        applicationContext, roomId,
                        PushMessageStore.Entry(event.eventId, event.seq, sender, preview, ts)
                    )
                    return Result.success()
                }

                // A sign-out - or sign-out then re-enroll - during the fetch
                // must not surface a stale preview: the session identity must
                // still be the one captured when this work was enqueued.
                val now = Session(applicationContext)
                if (!now.enrolled || now.sessionId != startSessionId) return Result.success()

                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // If the generic was dismissed (or the room read), don't post
                // a brand-new alert hours later; just move the watermark.
                val stillShowing = manager.activeNotifications.any {
                    it.tag == roomId && it.id == HomeRoomMessagingService.ID_MESSAGES
                }
                if (stillShowing) {
                    // Recent history comes only from this device's own
                    // /owner/events/{id} fetches (PushMessageStore), so the
                    // background path stays off the idle-extending routes.
                    val entries = PushMessageStore.append(
                        applicationContext, roomId,
                        PushMessageStore.Entry(event.eventId, event.seq, sender, preview, ts)
                    )
                    entries.forEach { e ->
                        style.addMessage(
                            NotificationCompat.MessagingStyle.Message(
                                e.text, e.ts,
                                androidx.core.app.Person.Builder().setName(e.sender).build()
                            )
                        )
                    }
                    // Tag = roomId: notification identity cannot collide
                    // across rooms; the enriched post replaces the generic.
                    manager.notify(roomId, HomeRoomMessagingService.ID_MESSAGES,
                        notification.setStyle(style).build())
                    HomeRoomMessagingService.postGroupSummary(applicationContext)
                }
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
        if (event.actorName != event.actorId) return event.actorName.takeIf { it != event.actorId } ?: "Member"
        val cached = session.roomsCache ?: return event.actorName.takeIf { it != event.actorId } ?: "Member"
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
            name ?: "Member"
        } catch (e: Exception) {
            event.actorName
        }
    }

    private fun resolveRoomName(session: Session, roomId: String): String? {
        val cached = session.roomsCache ?: return null
        return try {
            val rooms = org.json.JSONArray(cached)
            for (i in 0 until rooms.length()) {
                val room = Room.from(rooms.getJSONObject(i))
                if (room.id == roomId) return room.name
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val KEY_ROOM_ID = "room_id"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ENQUEUED_AT = "enqueued_at"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_AGE_MS = 60 * 60 * 1000L  // matches the server's push TTL
    }
}

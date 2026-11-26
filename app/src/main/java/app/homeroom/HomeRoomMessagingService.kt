package app.homeroom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives FCM messages. Per the privacy design, pushes carry only opaque
 * IDs - no message text, room name, or sender. A generic "New message in
 * HomeRoom" notification is posted immediately; a background worker then
 * replaces it in place with sender and preview fetched from your server with
 * the stored session, falling back to the generic one on failure.
 *
 * Ordering: the generic post and the worker's preview post share a per-room
 * lock and per-room seq watermark (push payloads carry the event's seq), so
 * a stale push can never cover a newer preview. Payloads without seq (older
 * server) fall back to posting the generic; the worker still guards order.
 */
class HomeRoomMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = Session(this)
        session.fcmToken = token
        if (session.enrolled && session.fcmRegisteredToken != token) {
            PushRegistration.register(serviceScope, session, ApiClient(session))
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val kind = message.data["kind"] ?: return
        if (kind != "new_message") return

        // A wake accepted by FCM before sign-out cannot be recalled: if this
        // device is signed out, drop it before posting or fetching anything.
        val session = Session(this)
        if (!session.enrolled) return

        val roomId = message.data["room_id"]

        // Delivery is at-least-once: skip events this device already showed.
        val eventId = message.data["event_id"]
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (eventId != null) {
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
            if (eventId in seen) return
            val updated = (seen + eventId).toList().takeLast(100).toSet()
            prefs.edit().putStringSet(KEY_SEEN, updated).apply()
        }

        // While the app is in the foreground the poller already shows new
        // messages; a system notification would only duplicate them.
        if (MainActivity.isForeground) return

        val seq = message.data["seq"]?.toLongOrNull()
        val lock = roomLocks.getOrPut(roomId ?: "") { Any() }
        synchronized(lock) {
            // A newer preview is already showing for this room; a stale push
            // must not cover it, even with the generic notification.
            if (roomId != null && seq != null &&
                seq < prefs.getLong("last_seq_" + roomId, -1)
            ) return

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (roomId != null) putExtra(EXTRA_ROOM_ID, roomId)
            }
            val pending = PendingIntent.getActivity(
                this, notificationId(roomId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = ensureChannel()
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_chats)
                .setContentTitle("HomeRoom")
                .setContentText("New message in HomeRoom")
                .setVisibility(genericVisibility(this))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(roomId, ID_MESSAGES, notification)
        }

        // Stage two: enrich the same notification with sender + preview from
        // the server. Runs as expedited work; failure leaves the generic one.
        if (roomId != null) {
            val work = androidx.work.OneTimeWorkRequestBuilder<NotificationFetchWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    androidx.work.workDataOf(
                        NotificationFetchWorker.KEY_ROOM_ID to roomId,
                        NotificationFetchWorker.KEY_EVENT_ID to eventId,
                        NotificationFetchWorker.KEY_SESSION_ID to session.sessionId,
                        NotificationFetchWorker.KEY_ENQUEUED_AT to System.currentTimeMillis(),
                    )
                )
                .build()
            androidx.work.WorkManager.getInstance(this).enqueue(work)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return CHANNEL_ID
    }

    companion object {
        // RECONSTRUCTED: WorkManager tagging is reconstruction-original (the exact
        // release build contains no WorkManager usage); constant kept for the
        // reconstruction's own workers.
        const val WORK_TAG = "homeroom_work"

        // RECONSTRUCTED: refresh the group-summary notification after per-room
        // notifications are cancelled.
        fun postGroupSummary(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val remaining = prefs.all.keys.count { it.startsWith("msgs_") }
            if (remaining == 0) manager.cancel(ID_SUMMARY)
        }
        /** The generic alert has no content, so it shows on the lock screen unless the user chose to hide lock-screen alerts. */
        fun genericVisibility(c: Context): Int =
            if (PrivacyPrefs.hidePreviews(c)) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC
        const val CHANNEL_ID = "messages"
        const val EXTRA_ROOM_ID = "app.homeroom.extra.ROOM_ID"
        const val PREFS_NAME = "homeroom_push"
        private const val KEY_SEEN = "seen_event_ids"

        /** Message notifications use the room id as the tag with this fixed
         *  id, so distinct rooms can never collide. */
        const val ID_MESSAGES = 1
        const val ID_SUMMARY = 2

        /** Per-room locks shared with NotificationFetchWorker. */
        val roomLocks = ConcurrentHashMap<String, Any>()

        /**
         * Stable request code for a room's PendingIntent (31 bits of the room
         * id's SHA-256; PendingIntent has no tag concept). Notification
         * identity itself is (tag = roomId, id = ID_MESSAGES), which cannot
         * collide.
         */
        fun notificationId(roomId: String?): Int {
            if (roomId == null) return 0
            val d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(roomId.toByteArray(Charsets.UTF_8))
            return (((d[0].toInt() and 0xFF) shl 24) or
                ((d[1].toInt() and 0xFF) shl 16) or
                ((d[2].toInt() and 0xFF) shl 8) or
                (d[3].toInt() and 0xFF)) and 0x7FFFFFFF
        }
    }
}
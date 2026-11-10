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
 * values: a room ID, an event ID and the server's global message sequence
 * number - no message text, room name, or sender. Because the sequence is a
 * server-wide running count, Google can see roughly how many messages the
 * whole server handles and when, not just the ones for this phone. Google's
 * FCM service also sees delivery timing and this device's Firebase
 * installation ID and push token. A
 * generic "New message in HomeRoom" notification is posted immediately; a
 * background worker then
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

        // Muted rooms are filtered server-side (native_fcm checks at enqueue
        // and again before send), so no local check here: a stale roster
        // cache must not suppress a room that was unmuted elsewhere.

        // Delivery is at-least-once: skip events this device already showed.
        val eventId = message.data["event_id"]
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (eventId != null) {
            // Ordered list, oldest first, capped at 100: a StringSet would
            // evict in arbitrary order.
            val seen = (prefs.getString(KEY_SEEN, "") ?: "")
                .split(',').filter { it.isNotEmpty() }
            if (eventId in seen) return
            prefs.edit().putString(KEY_SEEN, (seen + eventId).takeLast(100).joinToString(",")).apply()
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
                // PendingIntent equality includes the data URI, so this makes
                // each room's PendingIntent distinct with no requestCode math.
                if (roomId != null) {
                    data = android.net.Uri.parse("homeroom://room/" + android.net.Uri.encode(roomId))
                    putExtra(EXTRA_ROOM_ID, roomId)
                }
            }
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = ensureChannel()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val showing = roomId != null && manager.activeNotifications.any {
                it.tag == roomId && it.id == ID_MESSAGES
            }
            if (roomId != null && !showing) {
                // Nothing on screen for this room: anything stored was read
                // here or on another device, so the next stack starts fresh.
                PushMessageStore.clearRoom(this, roomId)
            }
            val showingStack = showing && roomId != null
            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_chats)
                .setContentTitle("HomeRoom")
                .setContentText("New message in HomeRoom")
                .setGroup(GROUP_KEY)
                // The plain generic is content-free (PUBLIC); once it carries
                // stored senders/text it must be PRIVATE with a generic
                // public version, like the worker's enriched post.
                .setVisibility(
                    if (showingStack) NotificationCompat.VISIBILITY_PRIVATE
                    else NotificationCompat.VISIBILITY_PUBLIC
                )
                .setAutoCancel(true)
                .setContentIntent(pending)
            if (showingStack) {
                builder.setPublicVersion(
                    NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_chats)
                        .setContentTitle("HomeRoom")
                        .setContentText("New message")
                        .build()
                )
            }
            if (showingStack) {
                // Keep the existing stack visible instead of flashing a bare
                // generic; the worker replaces it with the full history.
                val style = NotificationCompat.MessagingStyle(
                    androidx.core.app.Person.Builder().setName("HomeRoom").build()
                )
                PushMessageStore.entries(this, roomId).forEach { e ->
                    style.addMessage(
                        NotificationCompat.MessagingStyle.Message(
                            e.text, e.ts,
                            androidx.core.app.Person.Builder().setName(e.sender).build()
                        )
                    )
                }
                style.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        "New message", System.currentTimeMillis(),
                        androidx.core.app.Person.Builder().setName("HomeRoom").build()
                    )
                )
                builder.setStyle(style)
            }
            manager.notify(roomId, ID_MESSAGES, builder.build())
            postGroupSummary(this)
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
                .addTag(WORK_TAG)
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
        const val CHANNEL_ID = "messages"
        const val EXTRA_ROOM_ID = "app.homeroom.extra.ROOM_ID"
        const val PREFS_NAME = "homeroom_push"
        private const val KEY_SEEN = "seen_event_ids"

        /** Message notifications use the room id as the tag with this fixed
         *  id, so distinct rooms can never collide. */
        const val ID_MESSAGES = 1

        /** WorkManager tag for all pending preview fetches; cancelled on
         *  sign-out and when a session ends. */
        const val WORK_TAG = "push_fetch"

        /** Notification group for per-room message notifications. */
        const val GROUP_KEY = "homeroom_messages"

        /** Fixed id of the cross-room group summary notification. */
        const val ID_SUMMARY = 2

        /**
         * Posts (or refreshes) the cross-room summary. Alert-once: a new
         * child alerts, summary updates never re-alert.
         */
        fun postGroupSummary(context: Context) {
            val intent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chats)
                .setContentTitle("HomeRoom")
                .setContentText("New messages")
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ID_SUMMARY, summary)
        }

        /** Cancels the summary when no per-room message notifications remain. */
        fun cancelSummaryIfEmpty(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val childrenLeft = manager.activeNotifications.any {
                it.id == ID_MESSAGES
            }
            if (!childrenLeft) manager.cancel(ID_SUMMARY)
        }

        /** Per-room locks shared with NotificationFetchWorker. */
        val roomLocks = ConcurrentHashMap<String, Any>()
    }
}

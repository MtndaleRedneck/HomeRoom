package app.homeroom

import android.app.PendingIntent
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.*
import java.util.UUID

/** Explicit notification gestures are owner actions, not passive push enrichment. */
object NotificationActions {
    const val REPLY_TEXT="reply_text"
    fun add(context:Context,builder:NotificationCompat.Builder,room:String,event:String) {
        fun pending(action:String,mutable:Boolean):PendingIntent {
            val intent=Intent(context,NotificationActionReceiver::class.java).setAction(action)
                .setData(android.net.Uri.parse("homeroom://notification/"+android.net.Uri.encode(room)+"/"+action))
                .putExtra("room",room).putExtra("event",event)
                .putExtra("session",Session(context).sessionId)
            return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or if(mutable)PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        }
        val reply=NotificationCompat.Action.Builder(R.drawable.ic_send,"Reply",pending("reply",true))
            .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel("Reply").build()).setAllowGeneratedReplies(false).setAuthenticationRequired(true).build()
        builder.addAction(reply)
        builder.addAction(NotificationCompat.Action.Builder(R.drawable.ic_rules,"Mark as read",pending("read",false)).setAuthenticationRequired(true).build())
    }
}
class NotificationActionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val session=Session(context)
        if(!session.enrolled || session.sessionId!=intent.getStringExtra("session"))return
        val room=intent.getStringExtra("room") ?: return
        val event=intent.getStringExtra("event") ?: return
        val action=intent.action ?: return
        if(action !in listOf("reply","read"))return
        val text=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationActions.REPLY_TEXT)?.toString()?.trim().orEmpty()
        if(action=="reply" && text.isBlank())return
        val ids=(PushMessageStore.entries(context,room).map {it.eventId}+event).distinct().toTypedArray()
        val work=OneTimeWorkRequestBuilder<NotificationActionWorker>().setInputData(workDataOf("action" to action,"room" to room,"event" to event,"ids" to ids,"text" to text,"client" to UUID.randomUUID().toString(),"session" to session.sessionId)).addTag(HomeRoomMessagingService.WORK_TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("notification-action:"+room+":"+action,ExistingWorkPolicy.KEEP,work)
    }
}
class NotificationActionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val session=Session(applicationContext)
        if(!session.enrolled || session.sessionId!=inputData.getString("session"))return Result.success()
        val room=inputData.getString("room") ?: return Result.failure()
        val eventId=inputData.getString("event") ?: return Result.failure()
        return try {
            val api=ApiClient(session)
            val target=ChatEvent.from(api.event(eventId).getJSONObject("event"))
            if(target.roomId!=room)return Result.failure()
            if(inputData.getString("action")=="reply") {
                api.post(room,inputData.getString("client") ?: return Result.failure(),inputData.getString("text") ?: return Result.failure(),target.threadKey,target.eventId)
            } else {
                val events=mutableListOf<ChatEvent>()
                for(id in inputData.getStringArray("ids") ?: arrayOf(eventId)) {
                    val event=if(id==eventId)target else ChatEvent.from(api.event(id).getJSONObject("event"))
                    if(event.roomId==room && !event.threadKey.isNullOrBlank())events.add(event)
                }
                for((thread,list) in events.groupBy {it.threadKey!!})for(batch in list.chunked(200))api.markReadEvents(thread,batch.map {it.eventId})
            }
            val now=Session(applicationContext)
            if(now.sessionId==session.sessionId) {
                synchronized(HomeRoomMessagingService.roomLocks.getOrPut(room) {Any()}) {
                    (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(room,HomeRoomMessagingService.ID_MESSAGES)
                    PushMessageStore.clearRoom(applicationContext,room)
                    HomeRoomMessagingService.postGroupSummary(applicationContext)
                }
            }
            Result.success()
        } catch(e:SessionEndedException) {Result.failure()}
        catch(e:ApiException) {if(e.status==429 || e.status>=500)Result.retry() else {failureNotice(room);Result.failure()}}
        catch(_:Exception) {if(runAttemptCount<3)Result.retry() else {failureNotice(room);Result.failure()}}
    }
    private fun failureNotice(room:String) {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val intent=Intent(applicationContext,MainActivity::class.java).putExtra(HomeRoomMessagingService.EXTRA_ROOM_ID,room)
        val open=PendingIntent.getActivity(applicationContext,room.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(room,HomeRoomMessagingService.ID_MESSAGES,NotificationCompat.Builder(applicationContext,HomeRoomMessagingService.CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("HomeRoom action not confirmed").setContentText("Open the room to check and try again.").setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(applicationContext,HomeRoomMessagingService.CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("HomeRoom").setContentText("Action not confirmed").build()).build())
    }
}
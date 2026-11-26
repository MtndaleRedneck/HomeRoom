package app.homeroom

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.*

object TestAlert {
    const val EXTRA="homeroom_test_alert"
    fun received(context:Context,id:String) {
        if(!Session(context).enrolled || id.isBlank() || id.length>160)return
        receipt(context,id,"received")
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel="homeroom_test"
        if(manager.getNotificationChannel(channel)==null)manager.createNotificationChannel(android.app.NotificationChannel(channel,"Test alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).setData(android.net.Uri.parse("homeroom://test/"+android.net.Uri.encode(id))).putExtra(EXTRA,id)
        val open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())return
        try {
            manager.notify("test:"+id,14,NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("HomeRoom test alert").setContentText("Your app received this test. Tap to record opening it.")
                .setContentIntent(open).setAutoCancel(true).setVisibility(PrivacyPrefs.visibility(context)).build())
            receipt(context,id,"display_reported")
        }catch(_:SecurityException) {}
    }
    fun receipt(context:Context,id:String,stage:String) {
        val session=Session(context);if(!session.enrolled)return
        val work=OneTimeWorkRequestBuilder<TestReceiptWorker>().setInputData(workDataOf("id" to id,"stage" to stage,"client" to clientId(),"session" to session.sessionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag(HomeRoomMessagingService.WORK_TAG).build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
class TestReceiptWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val session=Session(applicationContext)
        if(!session.enrolled || session.sessionId!=inputData.getString("session"))return Result.failure()
        return try {
            V15Api(ApiClient(session)).testReceipt(inputData.getString("id") ?: return Result.failure(),inputData.getString("stage") ?: return Result.failure(),inputData.getString("client") ?: return Result.failure())
            Result.success()
        }catch(_:SessionEndedException) {Result.failure()}
        catch(_:Exception) {if(runAttemptCount<3)Result.retry() else Result.failure()}
    }
}
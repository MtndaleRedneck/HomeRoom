package app.homeroom

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.*

object ApprovalNotifications {
    const val EXTRA_PAGE="approval_page"
    fun show(context:Context,data:Map<String,String>) {
        val kind=data["kind"] ?: return
        val session=Session(context)
        if(!session.enrolled || kind !in listOf("key_pending","rule_pending"))return
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel="approvals"
        if(manager.getNotificationChannel(channel)==null)manager.createNotificationChannel(android.app.NotificationChannel(channel,"Approvals",NotificationManager.IMPORTANCE_HIGH))
        val ref=if(kind=="key_pending")data["key_id"] else data["rule_id"]
        if(ref.isNullOrBlank())return
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData(android.net.Uri.parse("homeroom://approval/"+android.net.Uri.encode(kind+":"+ref))).putExtra(EXTRA_PAGE,kind),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(if(kind=="key_pending")"AI key needs review" else "Rule needs your decision").setContentText("Open HomeRoom to review the current details.").setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("HomeRoom").setContentText("Review needed").build())
        b.addAction(0,"Review",open)
        // A key can never be approved without foreground fingerprint review.
        if(kind=="rule_pending" && data["version"]?.toIntOrNull()!=null) {
            for((label,decision) in listOf("Reject" to "reject","Approve" to "approve")) {
                val intent=Intent(context,ApprovalActionReceiver::class.java).setAction(decision).setData(android.net.Uri.parse("homeroom://rule-action/"+android.net.Uri.encode(ref)+"/"+decision))
                    .putExtra("rule",ref).putExtra("version",data["version"]!!.toInt()).putExtra("room",data["room_id"]).putExtra("session",session.sessionId)
                b.addAction(0,label,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        manager.notify("approval:"+ref,10,b.build())
    }
}
class ApprovalActionReceiver:android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val session=Session(context)
        if(!session.enrolled || session.sessionId!=intent.getStringExtra("session"))return
        val decision=intent.action?.takeIf {it in listOf("approve","reject")} ?: return
        val rule=intent.getStringExtra("rule") ?: return
        val work=OneTimeWorkRequestBuilder<ApprovalActionWorker>().setInputData(workDataOf("rule" to rule,"room" to intent.getStringExtra("room"),"version" to intent.getIntExtra("version",0),"session" to session.sessionId,"decision" to decision)).addTag(HomeRoomMessagingService.WORK_TAG).build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
class ApprovalActionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val session=Session(applicationContext)
        if(!session.enrolled || session.sessionId!=inputData.getString("session"))return Result.success()
        val rule=inputData.getString("rule") ?: return Result.success()
        val version=inputData.getInt("version",0)
        return try {
            val api=ApiClient(session)
            val current=RuleScope.from(api.rules(inputData.getString("room"))).pending.firstOrNull {it.ruleId==rule && it.version==version}
            if(current!=null)api.ruleDecision(rule,version,inputData.getString("decision") ?: return Result.success())
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel("approval:"+rule,10)
            Result.success()
        } catch(_:Exception) {Result.failure()}
    }
}
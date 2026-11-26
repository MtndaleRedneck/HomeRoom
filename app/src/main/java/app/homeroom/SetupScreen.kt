package app.homeroom

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection progress is based on actual enrollment/session and room-list responses, not a probe. */
class SetupScreen(context: Context) : Screen(context) {
    private val linkInput=input(context,"Connection link")
    internal val checklist=SetupChecklist(host.session.enrolled)
    private val rows=column(context)
    private val connectButton=button(context,"Connect") {connect()}
    private val continueButton=button(context,"Continue to rooms") {if(checklist.ready)host.onEnrolled()}
    private val alertsButton=button(context,"Allow notifications",false) {
        if(android.os.Build.VERSION.SDK_INT>=33)host.requestNotificationPermission()
        else host.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.packageName))
    }
    init {
        val body=column(context);pad(body,24,16)
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        body.addView(text(context,"HomeRoom",30f,C.text,true))
        body.addView(text(context,"Connect step by step",18f,C.text,true))
        body.addView(text(context,"Paste the whole one-use connection link from your server's connect page. Keep the link private.",14f,C.muted))
        linkInput.minLines=2;linkInput.gravity=Gravity.TOP
        linkInput.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        body.addView(linkInput,lp());body.addView(connectButton,lp())
        body.addView(rows,lp());body.addView(alertsButton,lp());body.addView(continueButton,lp())
        body.addView(text(context,"Notifications are optional. Allowing alerts does not prove they will arrive.",13f,C.muted))
        render()
    }
    override fun onShow() {render()}
    internal fun acceptHandoff(handoff:ConnectionHandoff,auto:Boolean) {
        if(checklist.busy || checklist.submitted || checklist.accepted)return
        linkInput.setText(handoff.link)
        if(auto)connect()
    }
    private fun render() {
        val allowed=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        checklist.notifications(allowed)
        rows.removeAllViews()
        val labels=listOf("1. Connection link","2. Phone reaches server","3. Sign-in accepted","4. Rooms loaded","5. Notifications allowed")
        SetupStep.entries.forEachIndexed {i,step ->
            val check=checklist.checks.getValue(step)
            rows.addView(text(context,labels[i]+": "+check.state,16f,if(check.state=="Passed")C.text else C.muted,true).apply {setPadding(0,dp(12),0,dp(3))})
            if(check.detail.isNotEmpty())rows.addView(text(context,check.detail,14f,C.muted))
            if(check.next.isNotEmpty())rows.addView(text(context,check.next,14f,C.text))
        }
        linkInput.visibility=if(checklist.accepted)View.GONE else View.VISIBLE
        linkInput.isEnabled=!checklist.busy && !checklist.submitted
        connectButton.text=when {checklist.busy->"Checking...";checklist.accepted && checklist.checks[SetupStep.SIGN_IN]?.state=="Passed"->"Retry room loading";checklist.accepted->"Retry sign-in check";else->"Connect"}
        connectButton.visibility=if(checklist.ready)View.GONE else View.VISIBLE
        connectButton.isEnabled=!checklist.busy && (!checklist.submitted || checklist.accepted)
        continueButton.visibility=if(checklist.ready)View.VISIBLE else View.GONE
        continueButton.text=if(allowed)"Continue to rooms" else "Continue without notifications"
        alertsButton.visibility=if(allowed)View.GONE else View.VISIBLE
        alertsButton.isEnabled=!checklist.busy
    }
    private fun connect() {
        if(checklist.busy)return
        val parsed=parseLink(linkInput.text.toString())
        if(!checklist.accepted && parsed!=null)host.session.serverUrl=parsed.first
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
        screenScope.launch {
            checklist.connect(parsed!=null,
                enroll={accepted->
                    val link=parsed ?: throw IllegalArgumentException("invalid_link")
                    host.api.enroll(link.second,"HomeRoom Android app") {
                        withContext(Dispatchers.Main) {linkInput.setText("");accepted()}
                    }
                },
                resume={host.api.bootstrap()},
                rooms={
                    val roster=host.api.roomRoster()
                    host.cacheRooms(roster.rooms);host.session.roomsCache=roster.rawRooms.toString()
                },changed={render()})
        }
    }
    companion object {
        internal fun parseLink(value:String):Pair<String,String>?=try {
            val uri=java.net.URI(value.trim())
            if(uri.scheme!="https" || uri.host.isNullOrBlank() || uri.userInfo!=null || uri.port !in listOf(-1,443))null
            else {
                val token=extractToken(value.trim())
                if(token==null || token.length>200 || token.any {it.isISOControl() || it.isWhitespace()})null
                else "https://"+uri.host to token
            }
        }catch(_:Exception) {null}
        fun normalizeServer(value:String):String?=try {
            val uri=java.net.URI(if("://" in value)value else "https://"+value)
            if(uri.scheme!="https" || uri.host.isNullOrBlank() || uri.userInfo!=null || uri.query!=null || uri.fragment!=null || uri.path !in listOf("","/"))null
            else java.net.URI("https",null,uri.host,uri.port,null,null,null).toString()
        }catch(_:Exception) {null}
        /** Decode the fragment exactly once. Ambiguous duplicate token fields are not accepted. */
        fun extractToken(link:String):String? {
            val raw=try {java.net.URI(link).rawFragment}catch(_:Exception) {null} ?: return null
            if(raw.isBlank())return null
            val fields=raw.split('&').map {it.split('=',limit=2)}
            val tokens=fields.filter {it.size==2 && it[0] in listOf("token","connect")}
            if(tokens.size>1)return null
            return Uri.decode(if(tokens.size==1)tokens.single()[1] else raw.takeIf {'=' !in it && '&' !in it} ?: return null).ifBlank {null}
        }
    }
}

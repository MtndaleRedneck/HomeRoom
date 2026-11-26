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
    private val strip=LinearLayout(context)
    private val connectButton=button(context,"Connect") {connect()}
    private val continueButton=button(context,"Continue to rooms") {if(checklist.ready)host.onEnrolled()}
    private val alertsButton=button(context,"Allow notifications",false) {
        if(android.os.Build.VERSION.SDK_INT>=33)host.requestNotificationPermission()
        else host.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.packageName))
    }
    init {
        val body=column(context);pad(body,24,16)
        addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))
        // 1.9: app tile, then the same wording, a
        // five-segment progress strip, and the steps in one card with status
        // dots. Step names, states, details and next lines are 1.8-verbatim.
        body.addView(text(context,"H",26f,C.onAccent,true).apply {gravity=Gravity.CENTER;background=accentFill(context,16f)},android.widget.LinearLayout.LayoutParams(dp(56),dp(56)).apply {bottomMargin=dp(16)})
        body.addView(text(context,"HomeRoom",30f,C.text,true))
        body.addView(text(context,"Connect step by step",18f,C.text,true))
        body.addView(text(context,"Paste the whole one-use connection link from your server's connect page. Keep the link private.",14f,C.muted))
        linkInput.minLines=2;linkInput.gravity=Gravity.TOP
        linkInput.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        body.addView(linkInput,lp());body.addView(connectButton,lp())
        strip.orientation=LinearLayout.HORIZONTAL
        body.addView(strip,android.widget.LinearLayout.LayoutParams(-1,dp(4)).apply {topMargin=dp(10);bottomMargin=dp(6)})
        val stepsCard=sectionCard(context)
        stepsCard.addView(rows,lp())
        body.addView(stepsCard,lp())
        // 1.9 mockup order: the primary Continue above the optional alerts row.
        body.addView(continueButton,lp());body.addView(alertsButton,lp())
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
        strip.removeAllViews()
        val labels=listOf("1. Connection link","2. Phone reaches server","3. Sign-in accepted","4. Rooms loaded","5. Notifications allowed")
        val steps=SetupStep.entries
        var firstOpen=-1
        steps.forEachIndexed {i,step ->
            val check=checklist.checks.getValue(step)
            if(firstOpen<0 && check.state!="Passed")firstOpen=i
            // Progress strip segment: accent when passed, dimmed accent for the
            // step in flight, raised card color for the rest.
            strip.addView(View(context).apply {
                background=rounded(if(check.state=="Passed") C.accent else C.surfaceAlt,dp(2).toFloat())
                if(check.state!="Passed" && i==firstOpen) background=rounded(androidx.core.graphics.ColorUtils.blendARGB(C.accent,C.surfaceAlt,0.5f),dp(2).toFloat())
            },android.widget.LinearLayout.LayoutParams(0,-1,1f).apply {rightMargin=dp(4)})
            val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(2))}
            // Status dot: green check when passed, clock while waiting, dash otherwise.
            val glyph=if(check.state=="Passed") "✓" else if(i==firstOpen) "…" else "–"
            val dotBg=if(check.state=="Passed") C.green else C.surfaceAlt
            row.addView(text(context,glyph,13f,if(check.state=="Passed") android.graphics.Color.parseColor("#1B1B1E") else C.muted,true).apply {gravity=Gravity.CENTER;background=rounded(dotBg,dp(12).toFloat())},android.widget.LinearLayout.LayoutParams(dp(24),dp(24)).apply {rightMargin=dp(12)})
            val copy=column(context)
            copy.addView(text(context,labels[i],15.5f,if(check.state=="Passed") C.text else C.muted,true))
            val stateLine=check.state+(if(check.detail.isNotEmpty()) " · "+check.detail else "")
            copy.addView(text(context,stateLine,13f,C.muted).apply {setPadding(0,dp(1),0,0)})
            if(check.next.isNotEmpty())copy.addView(text(context,check.next,14f,C.text))
            row.addView(copy,android.widget.LinearLayout.LayoutParams(0,-2,1f))
            rows.addView(row,android.widget.LinearLayout.LayoutParams(-1,-2))
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

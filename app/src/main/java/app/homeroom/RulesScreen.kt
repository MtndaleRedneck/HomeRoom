package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Rules preserve active and proposed versions as separate server facts. */
class RulesScreen(context:Context):Screen(context) {
    // RECONSTRUCTED: optional scope handoff when opened from an activity item.
    private var preparedScope: String? = null
    fun prepareScope(roomId: String?) { preparedScope = roomId }

    private val root=column(context)
    private val chips=LinearLayout(context)
    private val cards=column(context)
    private val note=text(context,"Loading rules",15f,C.muted)
    private var rooms=listOf<Room>()
    private var selected:String?=null
    private var scope=RuleScope(emptyList(),0,emptyMap())
    private var loading=false
    private val inFlight=mutableSetOf<String>()
    init {
        val aiNames=host.cachedRooms().flatMap { it.members }.distinctBy { it.first }.filter { it.first!=host.session.ownerId }.map { it.second }
        root.addView(text(context,"What "+aiNames.joinToString(" and ").ifBlank { "your AIs" }+" work by",13f,C.muted).apply { setPadding(dp(16),0,dp(16),dp(12)) })
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;addView(chips) })
        val scroll=ScrollView(context);scroll.addView(cards);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        cards.setPadding(dp(16),dp(8),dp(16),dp(110));cards.addView(note)
        addView(root,LayoutParams(-1,-1))
    }
    override fun onShow() {
        host.scope.launch {
            try { rooms=host.cachedRooms().ifEmpty { host.api.roomRoster().rooms };buildChips();load() }
            catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) { cards.removeAllViews();cards.addView(errorCard(context,"Couldn't load rules.") { onShow() }) }
        }
    }
    override fun poll() { load() }
    fun selectPendingScope(roomId:String?) { selected=roomId;buildChips();load() }
    private fun buildChips() {
        chips.removeAllViews();chips.setPadding(dp(16),0,dp(16),dp(8))
        val scopes=listOf(null to "Global")+rooms.map { it.id to it.name }
        for((id,name) in scopes) {
            val label=name+(if((id==null && scope.pendingCount>0 && selected==null) || rooms.firstOrNull { it.id==id }?.pendingRulesCount?.let { it>0 }==true) " •" else "")
            val chip=text(context,label,14f,if(id==selected) android.graphics.Color.WHITE else C.text,true).apply {
                if(label.endsWith(" •")) contentDescription=name+", a rule is waiting for your yes"
                gravity=Gravity.CENTER;background=rounded(if(id==selected) C.accent else C.surfaceAlt,dp(17).toFloat());setPadding(dp(14),0,dp(14),0)
                setOnClickListener { if(!loading) { selected=id;buildChips();load() } }
            }
            chips.addView(chip,LinearLayout.LayoutParams(-2,dp(34)).apply { rightMargin=dp(8) })
        }
    }
    private fun load() {
        if(loading)return
        loading=true
        val requested=selected
        host.scope.launch {
            try {
                val result=RuleScope.from(host.api.rules(requested))
                if(requested==selected) { scope=result;render();buildChips() }
            } catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) {
                cards.removeAllViews();cards.addView(errorCard(context,"Couldn't load rules.") { load() })
            } finally { loading=false }
        }
    }
    private fun actorName(actor:String)=if(actor==host.session.ownerId) host.api.sessionData?.optString("owner_display_name")?.ifBlank { "You" } ?: "You" else rooms.flatMap { it.members }.firstOrNull { it.first==actor }?.second ?: actor
    private fun section(label:String) { cards.addView(text(context,label,13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16);bottomMargin=dp(8) }) }
    private fun card()=column(context).apply { background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(16),dp(16),dp(16));cards.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }) }
    private fun render() {
        cards.removeAllViews()
        if(scope.pending.isNotEmpty()) section("Needs your yes · ${scope.pending.size}")
        for(rule in scope.pending) {
            val cell=card()
            val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            header.addView(text(context,"Proposed by "+actorName(rule.proposedBy)+" · v${rule.version}",13.5f,personColor(rule.proposedBy),true),LinearLayout.LayoutParams(0,-2,1f))
            header.addView(iconButton(context,R.drawable.ic_history,C.muted,"Version history") { history(rule) },LinearLayout.LayoutParams(dp(48),dp(48)))
            cell.addView(header)
            scope.active.firstOrNull { it.ruleId==rule.ruleId }?.let { old -> cell.addView(text(context,"Now: "+old.text,14f,C.muted),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }) }
            cell.addView(text(context,rule.text,15.5f,C.text))
            val actions=LinearLayout(context).apply { gravity=Gravity.END }
            for((label,decision) in listOf("Reject" to "reject","Approve" to "approve")) {
                val action=button(context,label,decision=="approve") { decide(rule,decision) }.apply { isEnabled=rule.ruleId !in inFlight;textSize=14f;minimumHeight=0;minHeight=0;setPadding(dp(16),0,dp(16),0);background=rounded(if(decision=="approve") C.accent else C.surface,dp(18).toFloat(),if(decision=="approve") null else 0xff3a4856.toInt()) }
                actions.addView(action,LinearLayout.LayoutParams(-2,dp(48)).apply { leftMargin=dp(8) })
            }
            cell.addView(actions,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        }
        val reread=card()
        val rrHeader=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        rrHeader.addView(text(context,"Last re-read",13f,C.muted,true),LinearLayout.LayoutParams(0,-2,1f))
        rrHeader.addView(button(context,"Record my re-read",false) { recordReread() },LinearLayout.LayoutParams(-2,dp(48)))
        reread.addView(rrHeader)
        val people=linkedSetOf<String>();host.session.ownerId?.let { people.add(it) };rooms.flatMap { it.members }.forEach { people.add(it.first) }
        for(actor in people) {
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(text(context,actorName(actor),15f,C.text),LinearLayout.LayoutParams(0,dp(40),1f))
            row.addView(text(context,scope.rereads[actor]?.let { formatRoomTime(it) } ?: "No re-read recorded",13f,if(scope.rereads[actor]==null) C.disabled else C.muted))
            reread.addView(row)
        }
        if(scope.active.isNotEmpty())section("Active · ${scope.active.size}")
        for(rule in scope.active) {
            val cell=card();cell.addView(text(context,rule.text,15.5f,C.text))
            cell.addView(text(context,"v${rule.version} · active · by "+actorName(rule.decidedBy ?: rule.proposedBy),12.5f,C.muted).apply { setPadding(0,dp(8),0,0) })
            cell.setOnClickListener { history(rule) }
        }
        if(scope.versions.isEmpty()) cards.addView(text(context,"No rules here yet.",15f,C.muted))
        // NOTE: unevidenced backdropChanged() call removed (no backdrop in release evidence).
    }
    private fun decide(rule:Rule,decision:String) {
        if(!inFlight.add(rule.ruleId))return
        render()
        host.scope.launch {
            try { host.api.ruleDecision(rule.ruleId,rule.version,decision);load() }
            catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(e:ApiException) { snack(this@RulesScreen,if(e.code=="rule_version_conflict") "This rule changed. Showing the newest version." else "Couldn't save that decision.");load() }
            catch(_:Exception) { snack(this@RulesScreen,"Couldn't save that decision.") }
            finally { inFlight.remove(rule.ruleId);render() }
        }
    }
    private fun recordReread() {
        host.scope.launch {
            try { host.api.recordReread(selected ?: return@launch);load() }
            catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) { snack(this@RulesScreen,"Couldn't record the re-read.") }
        }
    }
    private fun history(rule:Rule) {
        host.scope.launch {
            try {
                val data=host.api.ruleHistory(rule.ruleId)
                val entries=data.requiredArray("history").objects().sortedBy { it.optInt("version") }
                val body=entries.joinToString("\n\n") { "v${it.optInt("version")} · proposed by ${actorName(it.optString("proposed_by"))}\n${it.optString("text")}" }
                com.google.android.material.bottomsheet.BottomSheetDialog(context).apply {
                    val content=column(context).apply { setPadding(dp(16),dp(20),dp(16),dp(24));background=rounded(C.surface,dp(20).toFloat()) }
                    content.addView(text(context,"Version history",20f,C.text,true));content.addView(text(context,body.ifBlank { "No earlier versions." },14f,C.text).apply { setTextIsSelectable(true) })
                    setContentView(ScrollView(context).apply { addView(content) });showTracked()
                }
            } catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) { snack(this@RulesScreen,"Couldn't load the history.") }
        }
    }
}
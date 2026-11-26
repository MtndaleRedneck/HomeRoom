package app.homeroom

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

/** Chats tab: the room list with unread, waiting, and pending-rule badges. */
class RoomsScreen(context: Context) : Screen(context) {

    private val rows = mutableListOf<Room>()
    // RoomRoster envelope (K0/u3): the rooms response carries server_time and
    // global_pending_rules_count alongside the rooms array; the screen keeps
    // both for rendering (relative ages against the server clock, the
    // pending-rules state). Wired from the roster fetch; settable in tests.
    private var serverTime: String? = null
    private var globalPending: Int = 0
    private val adapter = Adapter()
    private val refresh: SwipeRefreshLayout
    private val empty = text(context, "No rooms yet.", 15f, C.muted)
    // 1.9 (owner-approved redesign): a strip above the list when rules wait on
    // the owner's yes; taps through to the Rules tab (existing destination).
    private val rulesStrip = LinearLayout(context)
    private var loading = false

    init {
        val root = column(context)
        rulesStrip.orientation = LinearLayout.HORIZONTAL
        rulesStrip.gravity = Gravity.CENTER_VERTICAL
        rulesStrip.visibility = View.GONE
        root.addView(rulesStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(C.accent)
            setProgressBackgroundColorSchemeColor(C.surface)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@RoomsScreen.adapter
            // Release list inset: the floating nav overlays the bottom 110dp.
            clipToPadding = false
            setPadding(0, 0, 0, dp(110))
        }
        refresh.addView(list)
        root.addView(refresh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        empty.visibility = View.GONE
        refresh.setOnRefreshListener { load(showSpinner = false) }
    }

    override fun onShow() {
        if (rows.isEmpty()) load(showSpinner = true)
    }

    override fun poll() = load(showSpinner = false)

    override fun onAccentChanged() {
        refresh.setColorSchemeColors(C.accent)
        renderRulesStrip()
        adapter.notifyDataSetChanged()
    }

    fun load(showSpinner: Boolean) {
        if (loading) return
        loading = true
        if (showSpinner) refresh.isRefreshing = true
        host.scope.launch {
            try {
                val roster = RoomRoster.from(host.api.roomsEnvelope())
                val rooms = roster.rooms
                serverTime = roster.serverTime
                globalPending = roster.globalPendingRulesCount
                host.cacheRooms(rooms)
                rows.clear()
                rows.addAll(rooms)
                renderRulesStrip()
                adapter.notifyDataSetChanged()
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e)
            } catch (e: Exception) {
                if (rows.isEmpty()) {
                    empty.text = "Couldn't load rooms. Pull to retry."
                    empty.visibility = View.VISIBLE
                }
            } finally {
                loading = false
                refresh.isRefreshing = false
            }
        }
    }

    private fun renderRulesStrip() {
        rulesStrip.removeAllViews()
        if (globalPending <= 0) { rulesStrip.visibility = View.GONE; return }
        rulesStrip.visibility = View.VISIBLE
        val card = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.surface, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            pressIn()
            setOnClickListener { host.openRulesTab() }
        }
        card.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.accent) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
        val label = if (globalPending == 1) "1 rule needs your yes" else "$globalPending rules need your yes"
        card.addView(text(context, label, 14f, C.text, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(text(context, "Review", 13f, C.accentText, true))
        rulesStrip.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val m = dp(16); setMargins(m, dp(8), m, dp(4))
        })
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val cell = LinearLayout(context)
            cell.gravity = Gravity.CENTER_VERTICAL
            pad(cell, 12, 10)
            cell.background = rounded(C.surface, context.dp(14).toFloat())
            val outer = LinearLayout(context)
            pad(outer, 16, 4)
            // RecyclerView generates WRAP_CONTENT default params for a root
            // without params, collapsing weight-based row content to 0 width
            // (NativeLayoutTest.roomAndMessageTextHaveRealWidth). Rows must
            // declare MATCH_PARENT before the RV adopts them.
            outer.layoutParams = android.view.ViewGroup.LayoutParams(-1, -2)
            outer.addView(cell, lp())
            return Holder(outer, cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val room = rows[position]
            val cell = holder.cell
            cell.removeAllViews()
            cell.addView(roomIcon(context, room, host.session.serverUrl, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
            val mid = column(context)
            val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(text(context, room.name, 16.5f, C.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val when_ = formatRoomTime(room.lastTime)
            if (when_.isNotEmpty()) titleRow.addView(text(context, when_, 11.5f, C.muted).apply { setPadding(dp(8), 0, 0, 0) })
            mid.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
            // Last-message preview, from the roster's retained last_post.
            val previewText = when {
                room.preview.isBlank() || room.preview == "No retained messages" -> room.preview
                room.lastOwn -> "You: " + room.preview
                !room.lastActor.isNullOrBlank() -> (room.members.firstOrNull { it.first == room.lastActor }?.second?.ifBlank { room.lastActor } ?: room.lastActor) + ": " + room.preview
                else -> room.preview
            }
            if (previewText.isNotBlank()) {
                mid.addView(text(context, previewText, 13.5f, C.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            // Member dots + waiting / rule-to-review markers (existing roster data).
            val metaRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            for (member in room.members.take(6)) {
                metaRow.addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(personColor(member.first)) }
                }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(3) })
            }
            val badges = mutableListOf<String>()
            if (room.waitingCount > 0) badges.add(room.waitingCount.toString() + " waiting")
            if (room.pendingRulesCount > 0) badges.add("rule to review")
            if (badges.isNotEmpty()) {
                metaRow.addView(text(context, badges.joinToString("  ·  "), 11.5f, C.accentText).apply { setPadding(dp(8), 0, 0, 0) })
            }
            mid.addView(metaRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            cell.addView(mid, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (room.unreadCount > 0) {
                val unread = text(context, if (room.unreadCount > 99) "99+" else room.unreadCount.toString(), 11.5f, C.onAccent, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(C.accent, dp(12).toFloat())
                    minWidth = dp(24); minHeight = dp(24)
                }
                cell.addView(unread, LinearLayout.LayoutParams(-2, dp(24)).apply { leftMargin = dp(10) })
            }
            cell.setOnClickListener { host.openRoom(room) }
        }

        override fun getItemCount() = rows.size
    }

    private class Holder(val root: View, val cell: LinearLayout) : RecyclerView.ViewHolder(root)
}

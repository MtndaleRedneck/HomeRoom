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
    private val adapter = Adapter()
    private val refresh: SwipeRefreshLayout
    private val empty = text(context, "No rooms yet.", 15f, C.muted)
    private var loading = false

    private val permissionBanner = text(context,
        "Notifications are off. Tap to enable so you hear about new messages.",
        13f, C.accent)

    init {
        permissionBanner.setOnClickListener { host.requestNotificationPermission() }
        pad(permissionBanner, 16, 10)
        permissionBanner.setBackgroundColor(C.surface)
        addView(permissionBanner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        permissionBanner.visibility = View.GONE

        refresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(C.accent)
            setProgressBackgroundColorSchemeColor(C.surface)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@RoomsScreen.adapter
        }
        refresh.addView(list)
        addView(refresh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        empty.visibility = View.GONE
        refresh.setOnRefreshListener { load(showSpinner = false) }
    }

    override fun onShow() {
        if (rows.isEmpty()) load(showSpinner = true)
        permissionBanner.visibility =
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) View.VISIBLE else View.GONE
    }

    override fun poll() = load(showSpinner = false)

    fun load(showSpinner: Boolean) {
        if (loading) return
        loading = true
        if (showSpinner) refresh.isRefreshing = true
        host.scope.launch {
            try {
                val raw = host.api.rooms()
                host.session.roomsCache = raw.toString()
                val rooms = raw.objects().map { Room.from(it) }
                host.cacheRooms(rooms)
                rows.clear()
                rows.addAll(rooms)
                adapter.notifyDataSetChanged()
                host.backdropChanged()
                empty.text="No rooms yet."
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                host.openPendingRoomIfLoaded()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                if (rows.isEmpty()) {
                    empty.text = if(e is ApiException && e.code.startsWith("invalid_response")) "Server response not understood. Pull to retry." else "Couldn't load rooms. Pull to retry."
                    empty.visibility = View.VISIBLE
                }
            } finally {
                loading = false
                refresh.isRefreshing = false
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val cell = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL }
            pad(cell, 16, 12)
            val outer = LinearLayout(context).apply {
                layoutParams=RecyclerView.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            outer.addView(cell, LinearLayout.LayoutParams(-1,-2))
            return Holder(outer, cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val room = rows[position]
            holder.cell.removeAllViews()
            val icon=roomIcon(context,room,host.session.serverUrl)
            holder.cell.addView(icon,LinearLayout.LayoutParams(dp(50),dp(50)).apply { rightMargin=dp(12) })
            val copy=column(context)
            val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            val name=text(context,room.name,16f,C.text,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            top.addView(name,LinearLayout.LayoutParams(0,-2,1f))
            if(room.lastTime.isNotBlank()) top.addView(text(context,formatTime(room.lastTime),10f,C.muted),LinearLayout.LayoutParams(-2,-2))
            copy.addView(top,LinearLayout.LayoutParams(-1,-2))
            val preview=text(context,room.preview.ifBlank { room.members.joinToString(", ") { it.second } },13f,C.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            copy.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(7) })
            val states=mutableListOf<String>()
            if(room.waitingCount>0) states.add("${room.waitingCount} waiting")
            if(room.pendingRulesCount>0) states.add("${room.pendingRulesCount} rule to review")
            if(room.muted) states.add("Muted")
            if(states.isNotEmpty()) copy.addView(text(context,states.joinToString(" · "),11f,C.accent),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            holder.cell.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            if(room.unreadCount>0) {
                val badge=text(context,room.unreadCount.toString(),12f,0xff1a1206.toInt(),true).apply { gravity=Gravity.CENTER; background=rounded(C.accent,dp(12).toFloat()); minWidth=dp(24) }
                holder.cell.addView(badge,LinearLayout.LayoutParams(-2,dp(24)).apply { leftMargin=dp(8) })
            }
            holder.cell.setOnClickListener { host.openRoom(room) }
            holder.cell.setOnLongClickListener { showRoomMenu(room); true }
        }

        private fun showRoomMenu(room: Room) {
            val muteLabel = if (room.muted) "Unmute notifications" else "Mute notifications"
            android.app.AlertDialog.Builder(context)
                .setTitle(room.name)
                .setItems(arrayOf(muteLabel)) { _, _ ->
                    val target = !room.muted
                    host.scope.launch {
                        try {
                            host.api.muteRoom(room.id, target)
                            // Keep the roster cache honest for the push path.
                            host.session.roomsCache = host.api.rooms().toString()
                            load(showSpinner = false)
                        } catch (e: Exception) {
                            snack(this@RoomsScreen, "Couldn't change mute. Try again.")
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun getItemCount() = rows.size
    }

    private class Holder(val root: View, val cell: LinearLayout) : RecyclerView.ViewHolder(root)
}

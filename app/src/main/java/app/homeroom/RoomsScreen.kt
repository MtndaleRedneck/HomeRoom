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
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                host.openPendingRoomIfLoaded()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
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

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val cell = column(context)
            pad(cell, 16, 14)
            cell.background = rounded(C.surface, context.dp(14).toFloat())
            val outer = LinearLayout(context)
            pad(outer, 16, 6)
            outer.addView(cell, lp())
            return Holder(outer, cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val room = rows[position]
            holder.cell.removeAllViews()
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = text(context, room.name, 17f, C.text, true)
            top.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val badges = mutableListOf<String>()
            if (room.muted) badges.add("muted")
            if (room.unreadCount > 0) badges.add(room.unreadCount.toString() + " unread")
            if (room.waitingCount > 0) badges.add(room.waitingCount.toString() + " waiting")
            if (room.pendingRulesCount > 0) badges.add("rule to review")
            if (badges.isNotEmpty()) {
                val b = text(context, badges.joinToString("  ·  "), 12f, C.accent)
                top.addView(b)
            }
            holder.cell.addView(top)
            if (room.members.isNotEmpty()) {
                holder.cell.addView(spacer(context, 4))
                holder.cell.addView(text(
                    context,
                    room.members.joinToString(", ") { it.second.ifBlank { it.first } },
                    13f, C.muted))
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

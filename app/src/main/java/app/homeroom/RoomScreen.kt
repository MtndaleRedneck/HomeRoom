package app.homeroom

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One room: message history with day separators, a "New messages" marker at
 * the saved place, older-page loading, polling for new messages, and the
 * compose bar with reply.
 */
class RoomScreen(context: Context, private val room: Room) : Screen(context) {

    private sealed class Row {
        data class Day(val label: String) : Row()
        data class Marker(val label: String) : Row()
        data class Message(val event: ChatEvent, val own: Boolean, val replyPreview: String?) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val events = mutableListOf<ChatEvent>()
    private val adapter = Adapter()
    private val list: RecyclerView
    private val layoutManager: LinearLayoutManager
    private val loadOlder = button(context, "Load older messages", primary = false) { loadOlder() }
    private val composeInput: EditText
    private val sendButton: ImageButton
    private val replyBanner: LinearLayout
    private val replyText = text(context, "", 13f, C.muted)
    private var replyTarget: ChatEvent? = null

    private var newestCursor: String? = null
    private var oldestCursor: String? = null
    private var hasMore = false
    private var loading = false
    private var initialPositioned = false
    private val pendingOwn = mutableSetOf<String>()

    init {
        val root = column(context)
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        loadOlder.visibility = View.GONE
        val olderWrap = LinearLayout(context)
        pad(olderWrap, 16, 6)
        olderWrap.addView(loadOlder, lp())
        root.addView(olderWrap)

        layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        list = RecyclerView(context).apply {
            layoutManager = this@RoomScreen.layoutManager
            adapter = this@RoomScreen.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        replyBanner = column(context)
        pad(replyBanner, 16, 6)
        replyBanner.setBackgroundColor(C.surface)
        val replyRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        replyRow.addView(replyText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val clear = text(context, "Cancel", 13f, C.accent)
        clear.setOnClickListener { setReply(null) }
        replyRow.addView(clear)
        replyBanner.addView(replyRow)
        replyBanner.visibility = View.GONE
        root.addView(replyBanner)

        val composeRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            setBackgroundColor(C.surface)
        }
        composeInput = input(context, "Message " + room.name)
        composeInput.maxLines = 4
        composeRow.addView(composeInput, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sendButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(Color.parseColor("#1a1206"))
            background = rounded(C.accent, context.dp(20).toFloat())
            contentDescription = "Send"
            setOnClickListener { send() }
        }
        val sendSize = context.dp(40)
        val sendLp = LinearLayout.LayoutParams(sendSize, sendSize).apply { leftMargin = context.dp(10) }
        composeRow.addView(sendButton, sendLp)
        root.addView(composeRow)
    }

    override fun onShow() {
        if (events.isEmpty()) loadInitial() else positionAtAnchor(force = true)
    }

    override fun poll() = loadNew()

    override fun onHide() {
        rememberPlace()
    }

    fun onResumeFromNav() = positionAtAnchor(force = true)

    private fun loadInitial() {
        if (loading) return
        loading = true
        host.scope.launch {
            try {
                val page = host.api.roomEvents(room.id)
                val pageEvents = page.optJSONArray("events")?.objects()?.map { ChatEvent.from(it) } ?: emptyList()
                newestCursor = page.optString("newest").ifBlank { null }
                oldestCursor = page.optString("oldest").ifBlank { null }
                hasMore = page.optBoolean("has_more", false)
                events.clear()
                events.addAll(pageEvents)
                rebuild()
                loading = false
                updateOlderVisibility()
                positionAtAnchor(force = true)
                markRead()
            } catch (e: SessionEndedException) {
                loading = false
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                loading = false
                snack(this@RoomScreen, "Couldn't load messages. Pull back and try again.")
            }
        }
    }

    private fun loadOlder() {
        if (loading || !hasMore) return
        val before = oldestCursor ?: return
        loading = true
        host.scope.launch {
            try {
                val page = host.api.roomEvents(room.id, before = before)
                val older = page.optJSONArray("events")?.objects()?.map { ChatEvent.from(it) } ?: emptyList()
                oldestCursor = page.optString("oldest").ifBlank { oldestCursor }
                hasMore = page.optBoolean("has_more", false)
                val known = events.map { it.eventId }.toHashSet()
                events.addAll(0, older.filter { it.eventId !in known })
                rebuild()
                updateOlderVisibility()
                loading = false
            } catch (e: SessionEndedException) {
                loading = false
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                loading = false
                snack(this@RoomScreen, "Couldn't load older messages.")
            }
        }
    }

    private fun loadNew() {
        if (loading) return
        val after = newestCursor ?: return
        loading = true
        host.scope.launch {
            try {
                val page = host.api.roomEvents(room.id, after = after)
                val fresh = page.optJSONArray("events")?.objects()?.map { ChatEvent.from(it) } ?: emptyList()
                newestCursor = page.optString("newest").ifBlank { newestCursor }
                val known = events.map { it.eventId }.toHashSet()
                val added = fresh.filter { it.eventId !in known }
                if (added.isNotEmpty()) {
                    val atBottom = isAtBottom()
                    added.forEach { it.clientId?.let(pendingOwn::remove) }
                    events.addAll(added)
                    rebuild()
                    if (atBottom) {
                        scrollToEnd()
                        markRead()
                    }
                }
                loading = false
            } catch (e: SessionEndedException) {
                loading = false
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                loading = false
            }
        }
    }

    private fun send() {
        val text = composeInput.text.toString().trim()
        if (text.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        val target = replyTarget
        composeInput.setText("")
        setReply(null)
        pendingOwn.add(clientId)
        host.scope.launch {
            try {
                host.api.post(
                    room.id, clientId, text,
                    threadKey = target?.threadKey ?: room.mainThreadKey,
                    parentId = target?.eventId,
                )
                loadNewForced()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                pendingOwn.remove(clientId)
                snack(this@RoomScreen, "Message didn't send. Try again.")
            }
        }
    }

    private fun loadNewForced() {
        loading = false
        loadNew()
        host.scope.launch {
            kotlinx.coroutines.delay(1500)
            if (!loading) loadNew()
        }
    }

    private fun setReply(target: ChatEvent?) {
        replyTarget = target
        if (target == null) {
            replyBanner.visibility = View.GONE
        } else {
            replyBanner.visibility = View.VISIBLE
            replyText.text = "Reply to " + target.actorName + ": " + target.text.take(80)
        }
    }

    private fun isAtBottom(): Boolean {
        val last = layoutManager.findLastVisibleItemPosition()
        return last >= rows.size - 2
    }

    private fun scrollToEnd() {
        if (rows.isNotEmpty()) list.scrollToPosition(rows.size - 1)
    }

    private fun rebuild() {
        rows.clear()
        val own = host.session.ownerId
        val anchorId = host.session.anchor(room.id)
        var anchorPlaced = false
        var lastDay = ""
        val sorted = events.sortedWith(compareBy({ it.createdAt }, { it.seq }))
        val latestId = sorted.lastOrNull()?.eventId
        for (event in sorted) {
            val day = dayLabel(event.createdAt)
            if (day != lastDay) {
                rows.add(Row.Day(day))
                lastDay = day
            }
            if (!anchorPlaced && anchorId != null && event.eventId == anchorId && event.eventId != latestId) {
                rows.add(Row.Marker("New messages"))
                anchorPlaced = true
            }
            val preview = event.parentId?.let { parent ->
                events.firstOrNull { it.eventId == parent }?.let { it.actorName + ": " + it.text.take(60) }
            }
            rows.add(Row.Message(event, own != null && event.actorId == own, preview))
        }
        adapter.notifyDataSetChanged()
    }

    private fun positionAtAnchor(force: Boolean) {
        if (initialPositioned && !force) return
        initialPositioned = true
        val anchorId = host.session.anchor(room.id)
        val index = if (anchorId != null) rows.indexOfFirst {
            it is Row.Message && it.event.eventId == anchorId
        } else -1
        if (index >= 0) list.scrollToPosition((index + 1).coerceAtMost(rows.size - 1))
        else scrollToEnd()
    }

    private fun updateOlderVisibility() {
        loadOlder.visibility = if (hasMore) View.VISIBLE else View.GONE
    }

    private fun markRead() {
        val mainKey = room.mainThreadKey ?: return
        val lastSeq = events.filter { it.threadKey == null || it.threadKey == mainKey }
            .maxOfOrNull { it.seq } ?: return
        rememberPlace()
        host.scope.launch {
            try {
                host.api.markRead(mainKey, lastSeq)
            } catch (e: Exception) {
                // Read state is best-effort; the next visit retries.
            }
        }
    }

    private fun rememberPlace() {
        events.maxWithOrNull(compareBy({ it.createdAt }, { it.seq }))?.let {
            host.session.setAnchor(room.id, it.eventId)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Day -> 0
            is Row.Marker -> 1
            is Row.Message -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                0, 1 -> {
                    val v = text(context, "", 12f, C.muted, true)
                    v.gravity = Gravity.CENTER
                    pad(v, 16, 8)
                    object : RecyclerView.ViewHolder(v) {}
                }
                else -> {
                    val outer = column(context)
                    pad(outer, 16, 3)
                    object : RecyclerView.ViewHolder(outer) {}
                }
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Day -> (holder.itemView as android.widget.TextView).text = row.label
                is Row.Marker -> {
                    val v = holder.itemView as android.widget.TextView
                    v.text = row.label
                    v.setTextColor(C.accent)
                }
                is Row.Message -> bindMessage(holder.itemView as LinearLayout, row)
            }
        }

        private fun bindMessage(container: LinearLayout, row: Row.Message) {
            container.removeAllViews()
            val event = row.event
            container.gravity = if (row.own) Gravity.END else Gravity.START

            val bubble = column(context)
            val bubbleColor = if (row.own) C.surfaceAlt else C.surface
            bubble.background = rounded(bubbleColor, context.dp(14).toFloat())
            bubble.setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            val bubbleLp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            bubbleLp.gravity = if (row.own) Gravity.END else Gravity.START
            bubbleLp.topMargin = context.dp(2)
            bubbleLp.bottomMargin = context.dp(2)

            if (!row.own) {
                bubble.addView(text(context, event.actorName, 12f, C.teal, true))
            }
            row.replyPreview?.let {
                val quote = text(context, it, 12f, C.muted)
                quote.setBackgroundColor(C.bg)
                quote.setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
                bubble.addView(quote)
                bubble.addView(spacer(context, 4))
            }
            val body = text(context, event.text, 15f, C.text)
            body.maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
            bubble.addView(body)
            bubble.addView(spacer(context, 2))
            val meta = text(context, formatTime(event.createdAt), 11f, C.muted)
            meta.gravity = Gravity.END
            bubble.addView(meta)

            bubble.setOnLongClickListener {
                setReply(event)
                true
            }
            container.addView(bubble, bubbleLp)
        }

        override fun getItemCount() = rows.size
    }
}

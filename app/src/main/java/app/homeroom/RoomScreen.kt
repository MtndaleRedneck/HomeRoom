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
    private var roomToolsWrap: LinearLayout? = null
    private val composeInput: EditText
    private val sendButton: ImageButton
    private val replyBanner: LinearLayout
    private val replyText = text(context, "", 13f, C.muted)
    private var replyTarget: ChatEvent? = null

    private var newestCursor: String? = null
    // Release chat screen keeps the events-envelope server_time (K0/W3:152
    // into e4.N; a missing value keeps the previous one). The binary's only
    // observed read is the waiting row's relative "no reply Xm" text
    // (e4:1684 via n1.a.R) - day labels stay on device time.
    private var serverTime: String? = null
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

        // private12 parity (K0/e4:210-227): the release's room screen carries a
        // "Room tools" button whose entries appear only while the server
        // advertises the matching feature flag. Hidden when none are on.
        val toolsWrap = LinearLayout(context)
        pad(toolsWrap, 16, 6)
        toolsWrap.addView(button(context, "Room tools · Attach, goals and more", primary = false) { showRoomTools() }, lp())
        toolsWrap.visibility = View.GONE
        roomToolsWrap = toolsWrap
        root.addView(toolsWrap)

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
        screenActive = true
        refreshRoomToolsVisibility()
        // Recover an accepted ask whose navigation was dropped by a dismiss:
        // retention is room-level, so a recreated screen still opens the
        // preserved answers page once it is active.
        val snap = host.session.authSnapshot()
        askAllPending.peekAccepted(snap.generation, room.id, snap.serverUrl)?.let { accepted ->
            // Peek-then-consume (I2 f409710 #3): the request_id is dropped
            // only after the page actually opened. Recovery uses a FRESH
            // snapshot of the verified-same identity - no stored credentials.
            try {
                host.openPage("AI answers", AskAllAnswersScreen(context, accepted.requestId, snap))
                askAllPending.dropAccepted(snap.generation, room.id, accepted.requestId, accepted.serverUrl)
            } catch (_: Exception) {
                // Retained for the next onShow; tell the user the recoverable
                // path instead of staying silent.
                snack(this, "Ask accepted. Reopen this room to see the answers.")
            }
        }
        if (events.isEmpty()) loadInitial() else positionAtAnchor(force = true)
    }

    // private12 parity (K0/e4 menu labels in release order, K0/A3 handlers):
    // each entry is listed only while the connected server advertises the
    // matching feature flag (ApiClient.hasFeature, fed by /owner/instance).
    private fun roomToolEntries(): List<Triple<String, String, () -> Unit>> {
        val entries = mutableListOf<Triple<String, String, () -> Unit>>()
        if (host.api.hasFeature("held_updates")) entries.add(Triple("held_updates", "Outgoing messages", { -> host.openPage("Outgoing messages", OutboxScreen(context)) }))
        if (host.api.hasFeature("ask_all")) entries.add(Triple("ask_all", "Ask AIs side by side", { -> beginAskAll() }))
        if (host.api.hasFeature("room_goals")) entries.add(Triple("room_goals", "Pinned room goal", { -> host.openPage("Pinned room goal", RoomGoalScreen(context, room)) }))
        if (host.api.hasFeature("staged_uploads")) entries.add(Triple("staged_uploads", "Attach photo or file", { -> attachFile() }))
        if (host.api.hasFeature("voice_notes")) entries.add(Triple("voice_notes", "Record voice note", { -> host.openPage("Record voice note", VoiceCapture(context, room)) }))
        return entries
    }

    private fun refreshRoomToolsVisibility() {
        roomToolsWrap?.visibility = if (roomToolEntries().isEmpty()) View.GONE else View.VISIBLE
    }

    // private12 parity: ask-all flow recovered from the exact release build -
    // roster gate (K0/w1: GET /owner/ais, room members filtered to roster),
    // dialog (m3/d.L: title, hint, per-AI checkboxes default-checked, caption),
    // validation (K0/v1: trimmed text 1..8000, >=1 AI), POST (K0/x1 -> N5.a),
    // then the AI answers page (K0/p3 mode 3). Error strings are the release's.
    private var askAllDialog: android.app.AlertDialog? = null
    // Screen-active lifecycle token (GPT/Gemini must-fix): View.isShown tracks
    // attach/visibility transitions, not the screen's logical active state, so
    // the roster await re-checks this flag instead.
    private var screenActive = false
    private var askAllGuardDialog: android.app.AlertDialog? = null
    // Unresolved-ask intent guard (I2): set BEFORE the POST starts, because an
    // in-flight ask whose dialog is dismissed is just as unresolved as a failed
    // one. A new dialog builds a NEW client_id (release parity), which does NOT
    // settle the possibly-accepted duplicate, so a further send is blocked while
    // this marker stands. Cleared only by proof of acceptance: the POST returns
    // a request_id, or a room reload surfaces an event carrying this client_id.
    // Retained ROOM-LEVEL (askAllPending in the companion), keyed by session
    // generation + room id, so leaving and re-entering the room keeps the
    // guard; in-memory only (process death policy in AskAllPendingStore).

    private fun beginAskAll() {
        // Operation-bound auth (I2 pre-certification finding): capture the
        // session identity at flow start and run every request in this flow
        // against it, so a session reset mid-flow is rejected by requestFor
        // instead of rebinding the old intent to a new session.
        val launch = host.session.authSnapshot()
        val blockedId = askAllPending.pendingClientId(launch.generation, room.id)
        if (blockedId != null) {
            // Block the blind resend, but offer explicit user resolution:
            // without an escape, a truly failed ask would lock the room for
            // the rest of the process lifetime.
            val guard = android.app.AlertDialog.Builder(context)
                .setTitle("Ask not confirmed")
                // Informed override (main 18:54): the user must know the old
                // ask may already have reached the AIs before choosing this.
                .setMessage("Your previous ask may already have been sent to the AIs. Sending again could post it twice. Check the room first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send anyway") { _, _ ->
                    // Re-prove identity at the tap (I2 f409710 #4): a
                    // pause/lock/session reset since this dialog was built
                    // must not let a stale tap mutate the guard.
                    val now = host.session.authSnapshot()
                    if (now.generation != launch.generation || now.serverUrl != launch.serverUrl) return@setPositiveButton
                    if (host.appLock.coverShowing) return@setPositiveButton
                    // A hide path (openRoom/openPage/closePage) that did not
                    // dismiss this guard must not let a tap on a hidden
                    // screen clear the marker or open the ask dialog.
                    if (!screenActive) return@setPositiveButton
                    // Compare-remove only the ask this dialog warned about,
                    // never a newer marker (I2 f409710 #1).
                    askAllPending.clearIfMatches(launch.generation, room.id, blockedId)
                    beginAskAll()
                }
                .create()
            // Transient tracking like the ask dialog: lock/reset paths own
            // it. One transient at a time (host contract): dismiss any
            // existing one (e.g. a previous guard) before assigning.
            host.transientDialog?.dismiss()
            host.transientDialog = guard
            askAllGuardDialog = guard
            guard.setOnDismissListener {
                if (askAllGuardDialog === guard) askAllGuardDialog = null
                if (host.transientDialog === guard) host.transientDialog = null
            }
            guard.show()
            return
        }
        screenScope.launch {
            val aiMembers: List<Pair<String, String>> = try {
                val roster = host.api.ais(launch).map { it.actor }.toSet()
                room.members.filter { it.first in roster }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: SessionEndedException) { host.onSessionEnded(e); return@launch
            } catch (_: Exception) {
                snack(this@RoomScreen, "Couldn't verify the room's AI members. Refresh before asking."); return@launch
            }
            if (aiMembers.isEmpty()) { snack(this@RoomScreen, "No AIs in this room."); return@launch }
            // Lifecycle guards after the await: the screen must still be the
            // active one, not covered by the app lock, and the session identity
            // must not have moved while the roster was in flight.
            if (!screenActive || host.appLock.coverShowing) return@launch
            if (host.session.authSnapshot().generation != launch.generation) return@launch
            showAskAllDialog(aiMembers, launch)
        }
    }

    private fun showAskAllDialog(aiMembers: List<Pair<String, String>>, launch: Session.OpAuth) {
        askAllDialog?.dismiss()
        val body = column(context).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val box = outlinedInput(context, "Question for these AIs")
        body.addView(box)
        val boxes = aiMembers.map { (actor, name) ->
            android.widget.CheckBox(context).apply {
                text = name.ifBlank { actor }
                isChecked = true
                setTextColor(C.text)
                tag = actor
                body.addView(this)
            }
        }
        body.addView(text(context, "Only selected AIs already granted this room can answer. No access is added.", 14f, C.muted))
        val askClientId = clientId()
        val createdAt = java.time.Instant.now().toString()
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Ask AIs side by side")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Ask", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = box.text.toString().trim()
                // Deduped and bounded to the server contract (actors 1-20 unique;
                // release validation in K0/v1 covers blank/>8000/empty).
                val selected = boxes.filter { it.isChecked }.map { it.tag as String }.distinct()
                if (selected.size > 20) {
                    // Contract bound is 1-20 unique actors; the release's generic
                    // error would mislead here (I2), so say what to do.
                    box.error = "Choose up to 20 AIs - uncheck some."
                    return@setOnClickListener
                }
                if (question.isBlank() || question.length > 8000 || selected.isEmpty()) {
                    box.error = "Enter a question and choose AIs."
                    return@setOnClickListener
                }
                val askButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                askButton.isEnabled = false
                // Atomic room claim BEFORE dispatch (GPT/Gem f409 #3): two
                // overlapping flows (a second screen, or dialogs racing a
                // roster await) can both pass the beginAskAll check - only the
                // first claim wins. A dismiss during the flight then leaves
                // the same possibly-accepted state as an explicit failure.
                if (!askAllPending.claim(launch.generation, room.id, askClientId)) {
                    // Claim lost to another in-flight flow whose marker names
                    // a DIFFERENT client_id: an in-dialog retry could never
                    // re-claim (only the identical-body failure retry of its
                    // OWN marker can). A re-enabled Ask here would be a dead
                    // button, so route to the same informed guard a fresh
                    // attempt hits (beginAskAll's blocked branch), where the
                    // user explicitly resolves the possibly-duplicate ask.
                    snack(this@RoomScreen, "Ask not confirmed. Check this room before resending.")
                    dialog.dismiss()
                    beginAskAll()
                    return@setOnClickListener
                }
                screenScope.launch {
                    // Stage 1: POST acceptance. An exception here means the ask
                    // may or may not be held by the server; the release (K0/x1)
                    // re-enables Ask but disables the inputs, so an in-dialog
                    // retry resends the IDENTICAL body with the same client_id
                    // (release parity; server-side same-ID dedup is the
                    // operator's contract question, never assumed here).
                    val response = try {
                        V15Api(host.api, launch).askAll(room.id, askClientId, createdAt, question, selected)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: SessionEndedException) { host.onSessionEnded(e); dialog.dismiss(); return@launch
                    } catch (_: Exception) {
                        snack(this@RoomScreen, "Ask not confirmed. Check this room before resending.")
                        askButton.isEnabled = true
                        box.isEnabled = false
                        boxes.forEach { it.isEnabled = false }
                        return@launch
                    }
                    // Stage 2: result parse. A missing or blank request_id after
                    // an accepted POST is the same uncertain state, same handling.
                    val requestId = response.nullableString("request_id")?.takeIf { it.isNotBlank() }
                    if (requestId == null) {
                        snack(this@RoomScreen, "Ask not confirmed. Check this room before resending.")
                        askButton.isEnabled = true
                        box.isEnabled = false
                        boxes.forEach { it.isEnabled = false }
                        return@launch
                    }
                    // Stage 3: navigation. Accepted, so the unresolved marker
                    // clears; if the dialog was dismissed during the POST the
                    // request_id is preserved and recovered as a real page from
                    // onShow instead of being dropped to a log line.
                    // Compare-remove: this acceptance clears only ITS marker,
                    // never a newer ask's (I2 f409710 #1).
                    askAllPending.clearIfMatches(launch.generation, room.id, askClientId)
                    // Retain room-level until the answers page actually opens,
                    // so recovery survives screen recreation (I2 18:50).
                    // Minimal identity only, no credentials (I2 f409710 #5).
                    askAllPending.retainAccepted(launch.generation, room.id, requestId, launch.serverUrl)
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        // openPage is outside the POST catch: a failure here is
                        // NOT an uncertain ask (the server accepted). Keep the
                        // retained request_id so onShow can recover the page;
                        // consumed only after the page opens (I2 f409710 #3).
                        try {
                            host.openPage("AI answers", AskAllAnswersScreen(context, requestId, launch))
                            askAllPending.dropAccepted(launch.generation, room.id, requestId, launch.serverUrl)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (_: Exception) {
                            snack(this@RoomScreen, "Ask accepted. Reopen this room to see the answers.")
                        }
                    }
                }
            }
        }
        askAllDialog = dialog
        host.transientDialog = dialog
        dialog.setOnDismissListener { askAllDialog = null; if (host.transientDialog === dialog) host.transientDialog = null }
        dialog.show()
    }

    private var roomToolsDialog: android.app.AlertDialog? = null

    private fun showRoomTools() {
        val entries = roomToolEntries()
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(context, "This server doesn't offer room tools.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Stale-context guard (I2 focused check, Nov 24): the dialog can be
        // retained across a session reset, so each dispatch re-proves the
        // session identity captured at menu build AND the server feature flag.
        val launch = host.session.authSnapshot()
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Room tools")
            .setItems(entries.map { it.second }.toTypedArray()) { d, which ->
                d.dismiss()
                val (flag, _, action) = entries[which]
                if (shouldDispatchEntry(host.session, launch, host.api.hasFeature(flag), host.appLock.coverShowing)) action()
            }
            .show()
        roomToolsDialog = dialog
        host.transientDialog = dialog
        dialog.setOnDismissListener {
            roomToolsDialog = null
            if (host.transientDialog === dialog) host.transientDialog = null
        }
    }

    // Release handler (K0/A3 case 2): the picker opens with */* and the chosen
    // file goes to the attachment composer for staging.
    private fun attachFile() {
        // Cross-session guard (ChatGPT finite review of 24c1422): the system
        // picker can outlive this screen, and MainActivity retains the callback
        // until the picker returns. Capture the operation snapshot at launch
        // and drop the result if the session changed while the picker was
        // open - otherwise AttachmentComposer would pair the old room with
        // the NEW session's auth snapshot taken at its construction. The
        // session-generation mechanism is reconstruction-side, so parity here
        // means following the tree's own OpAuth doctrine.
        val launch = host.session.authSnapshot()
        host.pickFile { uri ->
            // Bounded consistency (I2): the feature flag is re-proved at return
            // alongside the session identity; the composer itself defers its
            // auto-prepare while the lock cover is up.
            if (uri != null && shouldDeliverPick(uri, host.session, launch, host.api.hasFeature("staged_uploads"))) {
                host.openPage("Attach photo or file", AttachmentComposer(context, room, uri, null, voice = false))
            }
        }
    }

    internal companion object {
        // Process-level unresolved-ask guard (I2 379008b review): survives
        // RoomScreen recreation; in-memory only, scoped by session
        // generation + room id (policy in AskAllPendingStore).
        internal val askAllPending = AskAllPendingStore()

        // Picker-result gate, extracted for focused tests: deliver only a
        // non-null pick to an unchanged session (generation AND destination)
        // with the feature still advertised.
        fun shouldDeliverPick(uri: android.net.Uri?, session: Session, launch: Session.OpAuth, featureOn: Boolean): Boolean =
            uri != null && featureOn && session.unchangedSince(launch.generation, launch.serverUrl)

        // Menu-dispatch gate (I2 focused check): a retained menu entry fires
        // only for an unchanged session with the feature still advertised and
        // the app genuinely unlocked (a dialog window can sit above the cover).
        fun shouldDispatchEntry(session: Session, launch: Session.OpAuth, featureOn: Boolean, locked: Boolean): Boolean =
            featureOn && !locked && session.unchangedSince(launch.generation, launch.serverUrl)
    }

    override fun poll() = loadNew()

    override fun onHide() {
        screenActive = false
        roomToolsDialog?.dismiss()
        askAllDialog?.dismiss()
        askAllGuardDialog?.dismiss()
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
                page.optString("server_time").takeUnless { it.isBlank() }?.let { serverTime = it }
                newestCursor = page.optString("newest").ifBlank { null }
                oldestCursor = page.optString("oldest").ifBlank { null }
                hasMore = page.optBoolean("has_more", false)
                events.clear()
                events.addAll(pageEvents)
                rebuild()
                loading = false
                // Acceptance proof, not assumption: a reload clears the
                // duplicate guard only when an event carries the ask's own
                // client_id; a bare or empty fetch reconciles nothing.
                // Best-effort: server echo of client_id is unverified
                // (provenance note in AskAllPendingStore).
                askAllPending.reconcile(host.session.authSnapshot().generation, room.id, events.map { it.clientId })
                updateOlderVisibility()
                positionAtAnchor(force = true)
                markRead()
            } catch (e: SessionEndedException) {
                loading = false
                host.onSessionEnded(e)
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
                host.onSessionEnded(e)
            } catch (e: Exception) {
                loading = false
                snack(this@RoomScreen, "Couldn't load older messages.")
            }
        }
    }

    private fun loadNew() {
        if (loading || events.isEmpty()) return
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
                // Normal-refresh reconciliation (I2 f409710 #6): same
                // acceptance-proof rule as loadInitial, best-effort per the
                // store's provenance note.
                askAllPending.reconcile(host.session.authSnapshot().generation, room.id, fresh.map { it.clientId })
                loading = false
            } catch (e: SessionEndedException) {
                loading = false
                host.onSessionEnded(e)
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
                host.onSessionEnded(e)
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
        for (event in events.sortedWith(compareBy({ it.createdAt }, { it.seq }))) {
            val day = dayLabel(event.createdAt)
            if (day != lastDay) {
                rows.add(Row.Day(day))
                lastDay = day
            }
            if (!anchorPlaced && anchorId != null && event.eventId == anchorId && event.eventId != events.lastOrNull()?.eventId) {
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
                    // Create/bind contract: the binder expects a 3-child
                    // line - divider, pill label, divider (Day hides the
                    // dividers, Marker shows them). The original archive's
                    // bare TextView crashed the binder (ClassCastException).
                    val line = LinearLayout(context)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.gravity = Gravity.CENTER_VERTICAL
                    val left = View(context)
                    left.setBackgroundColor(C.accent)
                    line.addView(left, LinearLayout.LayoutParams(0, dp(1), 1f))
                    val v = text(context, "", 12f, C.muted, true)
                    v.gravity = Gravity.CENTER
                    line.addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8); rightMargin = dp(8) })
                    val right = View(context)
                    right.setBackgroundColor(C.accent)
                    line.addView(right, LinearLayout.LayoutParams(0, dp(1), 1f))
                    pad(line, 16, 8)
                    object : RecyclerView.ViewHolder(line) {}
                }
                else -> {
                    val outer = column(context)
                    pad(outer, 16, 3)
                    object : RecyclerView.ViewHolder(outer) {}
                }
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Day -> {
                    val line = holder.itemView as LinearLayout
                    val v = line.getChildAt(1) as android.widget.TextView
                    v.text = row.label; v.setTextColor(C.muted)
                    v.background = rounded(C.surface, dp(13).toFloat()); v.setPadding(dp(12), dp(4), dp(12), dp(4))
                    line.getChildAt(0).visibility = View.GONE; line.getChildAt(2).visibility = View.GONE
                }
                is Row.Marker -> {
                    val line = holder.itemView as LinearLayout
                    val v = line.getChildAt(1) as android.widget.TextView
                    v.text = row.label; v.setTextColor(C.accent); v.background = null; v.setPadding(0, 0, 0, 0)
                    line.getChildAt(0).visibility = View.VISIBLE; line.getChildAt(2).visibility = View.VISIBLE
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
                quote.background=android.graphics.drawable.LayerDrawable(arrayOf(rounded(C.bg,context.dp(8).toFloat()),rounded(C.accent,context.dp(2).toFloat()))).apply {setLayerInset(1,0,context.dp(3),context.dp(300),context.dp(3));setLayerWidth(1,context.dp(3))}
                quote.setPadding(context.dp(12), context.dp(4), context.dp(8), context.dp(4))
                bubble.addView(quote, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) // release: explicit (-2,-2), K0/N3:777
                bubble.addView(spacer(context, 4))
            }
            val body = text(context, event.text, 15f, C.text)
            // Release cap (K0/N3:708 and :750): message text maxWidth is
            // 0.72*display width (0.64 when the row also carries media, :776).
            body.maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
            // Release adds the text with explicit (-2,-2) (K0/N3:709) - the
            // vertical-LinearLayout MATCH_PARENT-width default collapses the
            // bubble in the wrap-content measure chain.
            bubble.addView(body, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            bubble.addView(spacer(context, 2))
            val meta = text(context, formatTime(event.createdAt), 11f, C.muted)
            meta.gravity = Gravity.END
            bubble.addView(meta)

            bubble.setOnLongClickListener {
                setReply(event)
                true
            }
            container.addView(bubble, bubbleLp)

            event.decisionId?.let { did ->
                // private12 parity (K0/N3:815-830): decision-linked messages get
                // a "Review decision" entry under the bubble that opens the
                // decision screen.
                val review = text(context, "Review decision", 13f, C.accent, true)
                review.setOnClickListener { host.openPage("Review decision", DecisionScreen(context, did)) }
                container.addView(review)
            }
        }

        override fun getItemCount() = rows.size
    }
}
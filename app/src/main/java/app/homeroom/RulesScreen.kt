package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Rules tab: the household rules for the whole server and per room. Pending
 * proposals can be approved or rejected, and a re-read can be recorded, the
 * same as the web view.
 */
class RulesScreen(context: Context) : Screen(context) {

    private val chips = LinearLayout(context)
    private val rules = mutableListOf<Rule>()
    private val adapter = Adapter()
    private val empty = text(context, "Loading rules...", 15f, C.muted)
    private var rooms: List<Room> = emptyList()
    private var selected: Room? = null  // null = global rules
    private var loading = false

    init {
        val root = column(context)
        val chipScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        pad(chips, 12, 8)
        chipScroll.addView(chips)
        root.addView(chipScroll)
        root.addView(divider(context))
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@RulesScreen.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        empty.gravity = Gravity.CENTER
        root.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onShow() {
        host.scope.launch {
            try {
                rooms = host.cachedRooms().ifEmpty {
                    host.api.rooms().objects().map { Room.from(it) }.also { host.cacheRooms(it) }
                }
                buildChips()
                load()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                empty.text = "Couldn't load rules. Reopen this tab to retry."
            }
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        val global = button(context, "Global rules", primary = selected == null) {
            selected = null
            buildChips()
            load()
        }
        chips.addView(global, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = context.dp(8)
        })
        for (room in rooms) {
            val chip = button(context, room.name, primary = room == selected) {
                selected = room
                buildChips()
                load()
            }
            chips.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.dp(8)
            })
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        empty.text = "Loading rules..."
        empty.visibility = View.VISIBLE
        host.scope.launch {
            try {
                val data = host.api.rules(selected?.id)
                val list = (data.optJSONArray("rules") ?: data.optJSONArray("items"))
                    ?.objects()?.map { Rule.from(it) } ?: emptyList()
                rules.clear()
                rules.addAll(list)
                adapter.notifyDataSetChanged()
                host.backdropChanged()
                empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "No rules here yet."
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                empty.text = "Couldn't load rules. Tap a tab above to retry."
            } finally {
                loading = false
            }
        }
    }

    private fun decide(rule: Rule, decision: String) {
        val verb = if (decision == "approve") "Approve" else "Reject"
        AlertDialog.Builder(context)
            .setTitle(verb + " this rule?")
            .setMessage(rule.text)
            .setPositiveButton(verb) { _, _ ->
                host.scope.launch {
                    try {
                        host.api.ruleDecision(rule.ruleId, rule.version, decision)
                        snack(this@RulesScreen, "Rule " + (if (decision == "approve") "approved." else "rejected."))
                        load()
                    } catch (e: SessionEndedException) {
                        host.onSessionEnded(e.reason)
                    } catch (e: ApiException) {
                        snack(this@RulesScreen, if (e.code == "version_conflict")
                            "This proposal changed. Reloading." else "Couldn't save that decision.")
                        load()
                    } catch (e: Exception) {
                        snack(this@RulesScreen, "Couldn't save that decision.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recordReread() {
        val room = selected ?: return
        host.scope.launch {
            try {
                host.api.recordReread(room.id)
                snack(this@RulesScreen, "Re-read recorded for " + room.name + ".")
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                snack(this@RulesScreen, "Couldn't record the re-read.")
            }
        }
    }

    private fun showHistory(rule: Rule) {
        host.scope.launch {
            try {
                val data = host.api.ruleHistory(rule.ruleId)
                val entries = (data.optJSONArray("history") ?: data.optJSONArray("versions"))
                    ?.objects() ?: emptyList()
                val text = if (entries.isEmpty()) "No earlier versions."
                else entries.joinToString("\n\n") { e: JSONObject ->
                    "v" + e.optInt("version", 1) + "  " + e.optString("status", "") + "\n" + e.optString("text")
                }
                AlertDialog.Builder(context)
                    .setTitle("Rule history")
                    .setMessage(text)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                snack(this@RulesScreen, "Couldn't load the history.")
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = column(context)
            pad(cell, 16, 12)
            cell.background = rounded(C.surface, context.dp(14).toFloat())
            val outer = LinearLayout(context)
            pad(outer, 16, 6)
            outer.addView(cell, lp())
            return object : RecyclerView.ViewHolder(outer) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val rule = rules[position]
            val cell = (holder.itemView as LinearLayout).getChildAt(0) as LinearLayout
            cell.removeAllViews()

            val statusLabel = when (rule.status) {
                "pending", "proposed" -> "Proposed by " + rule.proposedBy + "  ·  v" + rule.version
                else -> "Active  ·  v" + rule.version
            }
            cell.addView(text(context, statusLabel, 12f,
                if (rule.status == "active") C.muted else C.accent, true))
            cell.addView(spacer(context, 4))
            cell.addView(text(context, rule.text, 15f, C.text))
            cell.addView(spacer(context, 8))

            val actions = LinearLayout(context)
            if (rule.status == "pending" || rule.status == "proposed") {
                val approve = button(context, "Approve") { decide(rule, "approve") }
                val reject = button(context, "Reject", primary = false) { decide(rule, "reject") }
                actions.addView(approve, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = context.dp(8)
                })
                actions.addView(reject, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = context.dp(8)
                })
            }
            val history = button(context, "History", primary = false) { showHistory(rule) }
            actions.addView(history, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.dp(8)
            })
            if (selected != null) {
                val reread = button(context, "Record my re-read", primary = false) { recordReread() }
                actions.addView(reread)
            }
            cell.addView(actions)
        }

        override fun getItemCount() = rules.size
    }
}

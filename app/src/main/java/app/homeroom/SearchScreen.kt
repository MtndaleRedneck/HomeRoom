package app.homeroom

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search tab: message search across the server, or within one room. */
class SearchScreen(context: Context) : Screen(context) {

    private val query = input(context, "Search messages")
    private val hits = mutableListOf<SearchHit>()
    private val adapter = Adapter()
    private val empty = text(context, "Type to search all rooms.", 15f, C.muted)
    private var searchJob: Job? = null

    init {
        val root = column(context)
        val inputWrap = LinearLayout(context)
        pad(inputWrap, 16, 10)
        query.setSingleLine()
        query.imeOptions = EditorInfo.IME_ACTION_SEARCH
        query.setOnEditorActionListener { _, _, _ ->
            runSearch(query.text.toString())
            true
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                if (q.length >= 2) {
                    searchJob = host.scope.launch {
                        delay(400)
                        runSearch(q)
                    }
                }
            }
        })
        inputWrap.addView(query, lp())
        root.addView(inputWrap)
        root.addView(divider(context))
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SearchScreen.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        empty.gravity = Gravity.CENTER
        root.addView(empty)
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun runSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.length < 2) return
        empty.text = "Searching..."
        empty.visibility = View.VISIBLE
        host.scope.launch {
            try {
                val results = host.api.search(trimmed).objects().map { SearchHit.from(it) }
                hits.clear()
                hits.addAll(results)
                adapter.notifyDataSetChanged()
                empty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "No matches for \"" + trimmed + "\"."
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                empty.text = "Search didn't work. Try again."
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
            val hit = hits[position]
            val cell = (holder.itemView as LinearLayout).getChildAt(0) as LinearLayout
            cell.removeAllViews()
            cell.addView(text(context, hit.roomName + "  ·  " + formatTime(hit.createdAt), 12f, C.muted, true))
            cell.addView(spacer(context, 4))
            cell.addView(text(context, hit.excerpt, 15f, C.text))
            cell.setOnClickListener {
                val room = host.cachedRooms().firstOrNull { it.id == hit.roomId }
                if (room != null) host.openRoom(room) else snack(this@SearchScreen, "Open the room from the chats list.")
            }
        }

        override fun getItemCount() = hits.size
    }
}

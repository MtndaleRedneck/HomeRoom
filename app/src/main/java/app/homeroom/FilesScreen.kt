package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Files tab: files shared in each room, with image previews. Uploading files
 * from the app is not offered by the server API yet; this tab is browse and
 * preview only.
 */
class FilesScreen(context: Context) : Screen(context) {

    private val chips = LinearLayout(context)
    private val files = mutableListOf<Pair<String, RoomFile>>()
    private val adapter = Adapter()
    private val empty = text(context, "Pick a room to see its files.", 15f, C.muted)
    private var rooms: List<Room> = emptyList()
    private var selected: Room? = null
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
            adapter = this@FilesScreen.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        empty.gravity = Gravity.CENTER
        root.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onShow() {
        if (rooms.isEmpty()) {
            host.scope.launch {
                try {
                    rooms = host.cachedRooms().ifEmpty {
                        host.api.rooms().objects().map { Room.from(it) }.also { host.cacheRooms(it) }
                    }
                    buildChips()
                } catch (e: SessionEndedException) {
                    host.onSessionEnded(e.reason)
                } catch (e: Exception) {
                    empty.text = "Couldn't load rooms. Reopen this tab to retry."
                }
            }
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (room in rooms) {
            val chip = button(context, room.name, primary = room == selected) {
                select(room)
            }
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.rightMargin = context.dp(8)
            chips.addView(chip, lp)
        }
        if (rooms.size == 1) select(rooms.first())
    }

    private fun select(room: Room) {
        selected = room
        buildChips()
        load(room)
    }

    private fun load(room: Room) {
        if (loading) return
        loading = true
        empty.text = "Loading..."
        empty.visibility = View.VISIBLE
        host.scope.launch {
            try {
                val list = host.api.files(room.id).objects().map { RoomFile.from(it) }
                files.clear()
                files.addAll(list.map { room.name to it })
                adapter.notifyDataSetChanged()
                host.backdropChanged()
                empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "No files in " + room.name + " yet."
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                empty.text = "Couldn't load files. Tap the room again to retry."
            } finally {
                loading = false
            }
        }
    }

    private fun preview(file: RoomFile) {
        if (!file.isImage) {
            snack(this, "Preview is only available for images.")
            return
        }
        host.scope.launch {
            try {
                val (bytes, _) = host.api.filePreview(file.id)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    snack(this@FilesScreen, "Couldn't show that image.")
                    return@launch
                }
                val image = ImageView(context).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                AlertDialog.Builder(context)
                    .setTitle(file.name)
                    .setView(image)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: SessionEndedException) {
                host.onSessionEnded(e.reason)
            } catch (e: Exception) {
                snack(this@FilesScreen, "Couldn't load the preview.")
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
            val (roomName, file) = files[position]
            val cell = (holder.itemView as LinearLayout).getChildAt(0) as LinearLayout
            cell.removeAllViews()
            cell.addView(text(context, file.name, 16f, C.text, true))
            cell.addView(spacer(context, 4))
            val details = buildString {
                append(roomName)
                if (file.mimeType.isNotBlank()) append("  ·  " + file.mimeType)
                if (file.size > 0) append("  ·  " + humanSize(file.size))
            }
            cell.addView(text(context, details, 13f, C.muted))
            if (file.uploader.isNotBlank()) {
                cell.addView(text(context, "Shared by " + file.uploader, 13f, C.muted))
            }
            cell.setOnClickListener { preview(file) }
        }

        override fun getItemCount() = files.size
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }
}

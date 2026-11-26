package app.homeroom

import android.content.Context
import android.widget.ScrollView
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * RECONSTRUCTED Nov 23, 2026: add linked AIs to a room (release K0/k: "Add to",
 * room list). Writes the FULL member list via POST /owner/rooms/<id>/members,
 * matching the release members route. Exact release layout is not verified.
 */
internal class AddToChatScreen(context: Context, private val room: Room) : Screen(context) {
    private val body = column(context).apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }

    init { addView(ScrollView(context).apply { addView(body) }, LayoutParams(-1, -1)) }

    override fun onShow() { render() }

    private fun render() {
        body.removeAllViews()
        body.addView(text(context, "Add to " + room.name, 18f, C.text))
        body.addView(text(context, "Linked AIs that are not in this room yet.", 14f, C.muted))
        screenScope.launch {
            val candidates = try {
                host.api.ais().filter { ai -> room.members.none { it.first == ai.actor } }
            } catch (e: SessionEndedException) { host.onSessionEnded(e); return@launch }
            catch (_: Exception) {
                body.addView(errorCard(context, "Couldn't load your AIs.") { render() })
                return@launch
            }
            body.removeAllViews()
            body.addView(text(context, "Add to " + room.name, 18f, C.text))
            if (candidates.isEmpty()) {
                body.addView(text(context, "Every linked AI is already in this room.", 14f, C.muted))
                return@launch
            }
            for (ai in candidates) {
                body.addStacked(button(context, "Add " + ai.displayName, false) { add(ai) })
            }
        }
    }

    private fun add(ai: OwnerAi) {
        screenScope.launch {
            try {
                val members = room.members.map { it.first } + ai.actor
                host.api.request("/owner/rooms/" + ApiClient.enc(room.id) + "/members", "POST", JSONObject()
                    .put("members", org.json.JSONArray(members))
                    .put("metadata_version", room.metadataVersion))
                val roster = host.api.roomRoster().rooms
                host.cacheRooms(roster)
                snack(this@AddToChatScreen, ai.displayName + " added to this room.")
                render()
            } catch (e: SessionEndedException) { host.onSessionEnded(e) }
            catch (_: Exception) { snack(this@AddToChatScreen, "Couldn't add. Refresh before trying again.") }
        }
    }
}

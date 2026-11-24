package app.homeroom

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-activity app: a connection screen until the device is enrolled, then
 * a tab bar (Chats, Files, Rules, Search, Settings) with room views pushed on
 * top. A light foreground poller keeps the visible screen fresh.
 */
class MainActivity : AppCompatActivity() {

    lateinit var session: Session
        private set
    lateinit var api: ApiClient
        private set
    val scope = lifecycleScope

    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var header: LinearLayout
    private lateinit var headerBack: View
    private lateinit var headerTitle: android.widget.TextView

    private val tabScreens = mutableMapOf<Int, Screen>()
    private var currentTab: Int = R.id.nav_chats
    private var roomScreen: RoomScreen? = null
    private var roomCache: List<Room> = emptyList()
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // RECONSTRUCTED: release picks theme colors from the system night-mode flag.
        C.light = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO
        time24Hour = android.text.format.DateFormat.is24HourFormat(this)
        super.onCreate(savedInstanceState)
        session = Session(this)
        api = ApiClient(session)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(C.bg)

        header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            setBackgroundColor(C.bg)
        }
        headerBack = android.widget.TextView(this).apply {
            text = "<"
            textSize = 22f
            setTextColor(C.accent)
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        headerTitle = android.widget.TextView(this).apply {
            textSize = 18f
            setTextColor(C.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(headerBack, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        nav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.bottom_nav)
            setBackgroundColor(C.surface)
            itemIconTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.accent, C.muted)
            )
            itemTextColor = itemIconTintList
        }
        root.addView(nav)
        setContentView(root)

        nav.setOnItemSelectedListener { item ->
            if (roomScreen == null) showTab(item.itemId)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (roomScreen == null && currentPage != null && closePage()) {
                    // consumed
                } else if (roomScreen != null) closeRoom()
                else if (currentTab != R.id.nav_chats) {
                    nav.selectedItemId = R.id.nav_chats
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (session.enrolled) {
            showMain()
            UpdateCheck.checkInBackground(scope)
        } else {
            showSetup(null)
        }
        startPoller()
        registerConnectivity()
        onConnectIntent(intent)
        onShareIntent(intent)
    }

    // RECONSTRUCTED from the exact private12 release build (MainActivity.K /
    // P / Q and the K0.y1 handoff value): a homeroom://connect VIEW intent is
    // validated by ConnectionHandoff (same checks as the release), confirmed
    // with the user when it names a different server, then fills the setup
    // screen's link and starts connecting.
    private var pendingHandoff: ConnectionHandoff? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        onConnectIntent(intent)
        onShareIntent(intent)
    }

    // RECONSTRUCTED from the exact private12 release build (MainActivity.J):
    // ACTION_SEND with a content:// stream opens a "Review shared file in
    // room" picker over the non-archived rooms and hands the pick to the
    // attachment composer (which stages a private copy immediately, so the
    // transient read grant is enough). Guards mirror the release: unenrolled
    // or no rooms loaded -> toast; unreadable share -> toast. The intent is
    // consumed after reading so rotation does not retrigger it. The release's
    // busy-deferral copy and the homeroom_test_alert / approval_page extras
    // in J are NOT reconstructed (noted in docs/SOURCE-GAPS.md).
    private fun onShareIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND ||
            !intent.hasExtra(android.content.Intent.EXTRA_STREAM)) return
        val uri: android.net.Uri? = try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        } catch (e: Exception) { null }
        intent.action = null
        intent.removeExtra(android.content.Intent.EXTRA_STREAM)
        if (!session.enrolled) {
            android.widget.Toast.makeText(this, "Connect HomeRoom before sharing a file.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (uri != null && uri.scheme == "content") {
            val rooms = roomCache.filter { !it.archived }
            if (rooms.isEmpty()) {
                android.widget.Toast.makeText(this, "Open your room list before sharing a file.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val names = rooms.map { it.name }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Review shared file in room")
                .setItems(names) { _, which ->
                    openPage("Review shared file in room", AttachmentComposer(this, rooms[which], uri))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            android.widget.Toast.makeText(this, "This shared file couldn't be read.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun onConnectIntent(intent: android.content.Intent?) {
        if (!ConnectionHandoff.matches(intent)) return
        intent!!
        val handoff = ConnectionHandoff.from(intent)
        // Consume the intent so rotation/re-create does not retrigger it
        // (release clears data and extras the same way).
        intent.data = null
        intent.removeExtra("server_origin")
        intent.removeExtra("enrollment_code")
        if (handoff == null) {
            android.widget.Toast.makeText(this, "That connection link isn't valid.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        pendingHandoff = handoff
        consumeHandoff()
    }

    private fun consumeHandoff() {
        val handoff = pendingHandoff ?: return
        pendingHandoff = null
        val saved = session.serverUrl?.trimEnd('/')
        if (session.enrolled) {
            val msg = if (saved == handoff.origin) "Already signed in to this server."
                else "Sign out first before connecting to a different server."
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (saved == handoff.origin) {
            fillHandoff(handoff)
        } else {
            val host = try { java.net.URI(handoff.origin).host } catch (e: Exception) { handoff.origin }
            android.app.AlertDialog.Builder(this)
                .setTitle("Connect to this server?")
                .setMessage(host.toString() + "\n\nOnly continue if this is your intended HomeRoom server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect") { _, _ -> fillHandoff(handoff) }
                .show()
        }
    }

    private fun fillHandoff(handoff: ConnectionHandoff) {
        showSetup(null)
        (content.getChildAt(0) as? SetupScreen)?.acceptHandoff(handoff, auto = true)
    }

    // RECONSTRUCTED Nov 23, 2026: connectivity surface recovered from the exact
    // private12 release build — the activity tracks the validated-network flag,
    // tells the visible screen, and signals a refresh when connectivity returns.
    var online: Boolean = true
        private set

    private fun refreshOnline() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val was = online
        val network = cm.activeNetwork
        online = network != null && (cm.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val screen = currentPage ?: roomScreen
        screen?.onConnectivity(online)
        if (online && !was) screen?.onShow()
    }

    private fun registerConnectivity() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { runOnUiThread { refreshOnline() } }
            override fun onLost(network: android.net.Network) { runOnUiThread { refreshOnline() } }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { runOnUiThread { refreshOnline() } }
        })
        refreshOnline()
    }

    // RECONSTRUCTED Nov 23, 2026: page back-stack recovered from the exact
    // private12 release build (push current page state, swap content, show back,
    // hide nav, onShow; back pops and restores). The release also saves/restores
    // header-search and header-icon visibility, which this reconstruction's header
    // does not have yet - tracked as a gap.
    private data class PageState(val screen: Screen, val title: String, val backVisible: Int, val navVisible: Int, val room: RoomScreen?)
    private val pageStack = java.util.ArrayDeque<PageState>()
    private var currentPage: Screen? = null

    fun openPage(title: String, screen: Screen) {
        val current = content.getChildAt(0) as? Screen
        if (current != null) {
            current.onHide()
            pageStack.addLast(PageState(current, headerTitle.text.toString(), headerBack.visibility, nav.visibility, roomScreen))
        }
        roomScreen = null
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        currentPage = screen
        headerTitle.text = title
        headerBack.visibility = View.VISIBLE
        nav.visibility = View.GONE
        screen.onShow()
    }

    private fun closePage(): Boolean {
        val state = pageStack.pollLast() ?: return false
        (content.getChildAt(0) as? Screen)?.onHide()
        content.removeAllViews()
        content.addView(state.screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        currentPage = state.screen
        roomScreen = state.room
        headerTitle.text = state.title
        headerBack.visibility = state.backVisible
        nav.visibility = state.navVisible
        state.screen.onShow()
        return true
    }

    private fun showSetup(message: String?) {
        nav.visibility = View.GONE
        header.visibility = View.GONE
        roomScreen = null
        val setup = SetupScreen(this)
        if (message != null) {
            setup.post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        content.removeAllViews()
        content.addView(setup, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showMain() {
        nav.visibility = View.VISIBLE
        header.visibility = View.VISIBLE
        content.removeAllViews()
        tabScreens.clear()
        showTab(R.id.nav_chats)
    }

    fun onEnrolled() {
        showMain()
        UpdateCheck.checkInBackground(scope)
    }

    fun onSessionEnded(reason: String?) {
        session.cookie = null
        api = ApiClient(session)
        showSetup("Session ended. Ask for a new connection link.")
    }

    fun onSignedOut() {
        api = ApiClient(session)
        showSetup(null)
    }

    private fun tabFor(id: Int): Screen = tabScreens.getOrPut(id) {
        when (id) {
            R.id.nav_files -> FilesScreen(this)
            R.id.nav_rules -> RulesScreen(this)
            R.id.nav_search -> SearchScreen(this)
            R.id.nav_settings -> SettingsScreen(this)
            else -> RoomsScreen(this)
        }
    }

    private fun showTab(id: Int) {
        currentTab = id
        currentPage = null
        pageStack.clear()
        val screen = tabFor(id)
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerBack.visibility = View.GONE
        headerTitle.text = when (id) {
            R.id.nav_files -> "Files"
            R.id.nav_rules -> "Rules"
            R.id.nav_search -> "Search"
            R.id.nav_settings -> "Settings"
            else -> "Chats"
        }
        screen.onShow()
    }

    // RECONSTRUCTED: the release persists the open room ("last_room_id") and
    // opens at an event by setting the room anchor before showing the room.
    fun openRoomAt(room: Room, eventId: String) {
        session.setAnchor(room.id, eventId)
        openRoom(room)
    }

    fun openRoom(room: Room) {
        session.lastRoomId = room.id
        val screen = RoomScreen(this, room)
        roomScreen = screen
        currentPage = null
        pageStack.clear()
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerBack.visibility = View.VISIBLE
        headerTitle.text = room.name
        nav.visibility = View.GONE
        screen.onShow()
    }

    private fun closeRoom() {
        val closing = roomScreen
        roomScreen = null
        closing?.onHide()
        nav.visibility = View.VISIBLE
        showTab(currentTab)
    }

    fun cacheRooms(rooms: List<Room>) {
        roomCache = rooms
    }

    fun cachedRooms(): List<Room> = roomCache

    private fun startPoller() {
        if (polling) return
        polling = true
        scope.launch {
            while (isActive) {
                delay(4000)
                if (!polling) break
                val screen = roomScreen ?: tabScreens[currentTab]
                screen?.poll()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        polling = false
    }

    override fun onResume() {
        isForeground = true
        super.onResume()
        startPoller()
        val screen = roomScreen ?: tabScreens[currentTab]
        screen?.poll()
    }

    // RECONSTRUCTED Nov 23, 2026: image picking for the profile avatar and
    // outbox draining. Standard activity-result picker; the release's exact
    // picker contract is not verified (registered gap).
    private var pickImageCallback: ((android.net.Uri?) -> Unit)? = null
    private val pickImageLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            val cb = pickImageCallback; pickImageCallback = null; cb?.invoke(uri)
        }

    fun pickImage(callback: (android.net.Uri?) -> Unit) {
        pickImageCallback = callback
        runCatching { pickImageLauncher.launch("image/*") }.onFailure { pickImageCallback = null; callback(null) }
    }

    // RECONSTRUCTED Nov 23, 2026: save a text document (room export) via SAF.
    private var saveDocumentCallback: ((Boolean) -> Unit)? = null
    private var saveDocumentPayload: String? = null
    private val saveDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val payload = saveDocumentPayload; saveDocumentPayload = null
            val cb = saveDocumentCallback; saveDocumentCallback = null
            val ok = if (uri != null && payload != null) runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) } != null
            }.getOrDefault(false) else false
            cb?.invoke(ok)
        }

    fun saveDocument(filename: String, contents: String, mimeType: String = "application/json", done: ((Boolean) -> Unit)? = null) {
        saveDocumentPayload = contents
        saveDocumentCallback = done
        runCatching { saveDocumentLauncher.launch(filename) }.onFailure {
            saveDocumentPayload = null; saveDocumentCallback = null; done?.invoke(false)
        }
    }

    // RECONSTRUCTED: app lock gate and privacy flags (blur in recents, secret
    // notification previews) applied activity-wide.
    val appLock: AppLock by lazy { AppLock(this) { /* unlocked: content already visible */ } }

    fun applyPrivacy() {
        if (PrivacyPrefs.hideRecents(this)) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun openMutedRooms() { openPage("Muted rooms", MutedRoomsScreen(this)) }

    // RECONSTRUCTED: Android 13+ notification permission prompt.
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    fun sendQueued() {
        scope.launch {
            try { Outbox(session).drain(api) }
            catch (e: SessionEndedException) { onSessionEnded(e.reason) }
            catch (_: Exception) {}
        }
    }

    companion object {
        // RECONSTRUCTED: foreground flag so push notifications do not duplicate
        // what the poller already shows.
        @Volatile var isForeground: Boolean = false; private set

        // RECONSTRUCTED: the release notifies the activity when update state
        // changes so the update banner can refresh. The banner UI is not yet
        // reconstructed (registered gap); the hook keeps the call surface exact.
        private var updateListener: (() -> Unit)? = null
        fun setUpdateListener(listener: (() -> Unit)?) { updateListener = listener }
        fun onUpdateChanged() { updateListener?.invoke() }
    }
}

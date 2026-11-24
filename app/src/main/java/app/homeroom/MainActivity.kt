package app.homeroom

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    // Restored release layout (K0): floating GlassNav over a navFade gradient,
    // both overlaying the main column, which is also the backdrop-capture source.
    private lateinit var nav: GlassNav
    private lateinit var navFade: View
    private lateinit var header: LinearLayout
    private lateinit var headerBack: View
    private lateinit var headerTitle: android.widget.TextView
    private lateinit var headerSearch: View
    private var displayedRoom: Room? = null
    // Explicit pause-block for the backdrop capture (I2 review Nov 24): the
    // cover/FLAG_SECURE predicate alone cannot block a capture enqueued after
    // onPause when neither holds, so backgrounding is its own blocked state.
    private var captureSuspended = false

    private val tabScreens = mutableMapOf<Int, Screen>()
    private var currentTab: Int = R.id.nav_chats
    private var roomScreen: RoomScreen? = null
    private var roomCache: List<Room> = emptyList()
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // RECONSTRUCTED: release picks theme colors from the system night-mode flag.
        C.light = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO
        use24Hour = android.text.format.DateFormat.is24HourFormat(this)
        super.onCreate(savedInstanceState)
        session = Session(this)
        api = ApiClient(session)
        // AppLock registers an activity-result launcher in its constructor;
        // AndroidX requires registerForActivityResult before the lifecycle
        // reaches STARTED, so the lazy gate must be forced here, not first
        // touched in onResume (runtime crash: "attempting to register while
        // current state is STARTED", debug-smoke acd48bf).
        appLock

        // RECONSTRUCTED Nov 24 from the exact private12 decompile (K0 root):
        // a FrameLayout stacking the main column (header + content), the navFade
        // gradient (110dp, bottom, transparent -> 0xb3 -> opaque bg, release
        // colors 0x00/0xb3/0xff of the theme background), and the floating
        // 256x64dp GlassNav centered 28dp off the bottom. The backdrop capture
        // source is the whole main column, matching the release's a2 source.
        val root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.setBackgroundColor(C.bg)

        header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
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
        // Release K0.a0: 48dp header search, "Search messages". Click (K0.l2):
        // on the Files tab with no open room, reveal Files' own inline search;
        // otherwise push a Search route scoped to the displayed room.
        headerSearch = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(C.muted)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Search messages"
            setOnClickListener {
                if (currentTab == R.id.nav_files && roomScreen == null && currentPage == null) {
                    (tabScreens[R.id.nav_files] as? FilesScreen)?.showSearch()
                } else {
                    openPage("Search", SearchScreen(this@MainActivity, displayedRoom?.id))
                }
            }
        }
        header.addView(headerBack, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerSearch, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(80)))

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        navFade = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(C.bg and 0x00ffffff, (C.bg and 0x00ffffff) or 0xb3000000.toInt(), C.bg)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(navFade, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(110), android.view.Gravity.BOTTOM))

        // Release K0.p: nav taps choose a tab. Wired BEFORE attach so the
        // first capture on attach already respects the capture-under-lock gate.
        nav = GlassNav(this, column) { id -> showTab(id) }
        nav.captureBlocked = {
            captureSuspended || appLock.coverShowing ||
                (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        root.addView(nav, FrameLayout.LayoutParams(dp(256), dp(64), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        setContentView(root)

        // The settings-tab update dot rides the release's update-state hook.
        setUpdateListener { if (::nav.isInitialized) nav.setUpdateAvailable(UpdateCheck.hasUpdate()) }
        nav.setUpdateAvailable(UpdateCheck.hasUpdate())

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            // Release back contract (exact private12 decompile): the visible
            // screen first (its onBack hook, e.g. dismissing transient UI),
            // then pop the route stack, then fall back to the Chats tab, then
            // the system default. Rooms are routes too: with the pushRoute
            // openRoom below, Back from a room returns to whatever was pushed
            // beneath it (e.g. Waiting), not unconditionally to Chats.
            override fun handleOnBackPressed() {
                if ((content.getChildAt(0) as? Screen)?.onBack() == true) {
                    // consumed by the visible screen
                } else if (closePage()) {
                    // consumed - popped a pushed route (page, Waiting, or tab root)
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
        onRoomIntent(intent)
    }

    // RECONSTRUCTED from the exact private12 release build (MainActivity.K /
    // P / Q and the K0.y1 handoff value): a homeroom://connect VIEW intent is
    // validated by ConnectionHandoff (same checks as the release), confirmed
    // with the user when it names a different server, then fills the setup
    // screen's link and starts connecting.
    private var pendingHandoff: ConnectionHandoff? = null

    // Public to match the release's MainActivity.onNewIntent (and so tests
    // can drive the notification route); the framework override is protected.
    public override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        onConnectIntent(intent)
        onShareIntent(intent)
        onRoomIntent(intent)
    }

    // RECONSTRUCTED Nov 24 from the exact private12 decompile (MainActivity.J
    // ROOM_ID block + R): a notification tap carrying a room id consumes the
    // extra, requires enrollment, unwinds whatever is showing to the Chats
    // tab root (showTab clears the route stack - Back from the opened room
    // must land on Chats, never on a stale room or page), refreshes the rooms
    // screen, and opens the room once it is in the cache. The release calls
    // R() only from J, so an uncached room never opened; here cacheRooms
    // retries the pending open, which is the refresh completion point.
    private var pendingOpenRoomId: String? = null

    private fun onRoomIntent(intent: android.content.Intent?) {
        val roomId = intent?.getStringExtra(HomeRoomMessagingService.EXTRA_ROOM_ID) ?: return
        intent.removeExtra(HomeRoomMessagingService.EXTRA_ROOM_ID)
        if (!session.enrolled) return
        pendingOpenRoomId = roomId
        showTab(R.id.nav_chats)
        if (nav.selectedItemId != R.id.nav_chats) nav.selectedItemId = R.id.nav_chats
        (tabScreens[R.id.nav_chats] as? RoomsScreen)?.load(showSpinner = false)
        tryOpenPendingRoom()
    }

    private fun tryOpenPendingRoom() {
        val roomId = pendingOpenRoomId ?: return
        val room = roomCache.firstOrNull { it.id == roomId } ?: return
        pendingOpenRoomId = null
        openRoom(room)
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
        if (session.enrolled) {
            val saved = session.serverUrl?.trimEnd('/')
            val msg = if (saved == handoff.origin) "Already signed in to this server."
                else "Sign out first before connecting to a different server."
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // RECONSTRUCTION-ORIGINAL hardening (deliberate divergence from the
        // release's same-origin auto-connect, I2 finding): while NOT enrolled,
        // a remembered destination is not permission. A failed or cancelled
        // earlier attempt leaves session.serverUrl saved, so auto-connecting on
        // origin match would bypass consent. Every handoff in this state gets an
        // explicit enrollment decision showing the verified destination host.
        val host = try { java.net.URI(handoff.origin).host } catch (e: Exception) { handoff.origin }
        android.app.AlertDialog.Builder(this)
            .setTitle("Connect to this server?")
            .setMessage(host.toString() + "\n\nOnly continue if this is your intended HomeRoom server.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ -> fillHandoff(handoff) }
            .show()
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
    // hide nav, onShow; back pops and restores). Like the release, the saved
    // state also carries header-search visibility; the release's header-icon
    // slot has no counterpart here (tracked gap).
    private data class PageState(val screen: Screen, val title: String, val backVisible: Int, val navVisible: Int, val searchVisible: Int, val room: RoomScreen?)

    // navFade mirrors nav visibility (release K0.m2), kept in lockstep at
    // every assignment instead of via the release's layout listener.
    private fun setNavVisible(visibility: Int) {
        nav.visibility = visibility
        if (::navFade.isInitialized) navFade.visibility = visibility
    }

    // Capture-under-lock transitions (I2, Nov 24): pause, privacy-flag and
    // lock-cover transitions drop retained backdrop state; unblocking paths
    // request a fresh capture.
    fun invalidateNavBackdrop() { if (::nav.isInitialized) nav.invalidateBackdrop() }
    fun refreshNavBackdrop() { if (::nav.isInitialized) nav.backdropChanged() }
    private val pageStack = java.util.ArrayDeque<PageState>()
    private var currentPage: Screen? = null
    // Retained transient dialog slot: screens register activity-level dialogs
    // here so session reset (showSetup) can dismiss them (I2, Nov 24).
    internal var transientDialog: android.app.Dialog? = null

    // Deferred actions that must not run beneath the app-lock cover (I2
    // focused check, Nov 24): the cover blocks touches but not coroutines,
    // so side-effecting work started from a page's onShow waits for a
    // genuine unlock.
    private val afterUnlockActions = mutableListOf<() -> Unit>()

    fun runWhenUnlocked(action: () -> Unit) {
        if (!appLock.coverShowing) action() else synchronized(afterUnlockActions) { afterUnlockActions.add(action) }
    }

    private fun drainAfterUnlock() {
        val pending = synchronized(afterUnlockActions) { afterUnlockActions.toList().also { afterUnlockActions.clear() } }
        pending.forEach { runCatching { it() } }
    }

    fun openPage(title: String, screen: Screen) {
        val current = content.getChildAt(0) as? Screen
        if (current != null) {
            current.onHide()
            pageStack.addLast(PageState(current, headerTitle.text.toString(), headerBack.visibility, nav.visibility, headerSearch.visibility, roomScreen))
        }
        roomScreen = null
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        currentPage = screen
        headerTitle.text = title
        headerBack.visibility = View.VISIBLE
        headerSearch.visibility = View.GONE
        setNavVisible(View.GONE)
        screen.onShow()
    }

    // RECONSTRUCTED Nov 24: historical Nov 11 entry points (operator readback,
    // parsed as data), riding the restored release GlassNav overlay layout.
    // openWaiting rides the current page-stack navigation, which already
    // carries the same contract (pushed route, full-content screen, back
    // shown, nav hidden, onShow) as the historical body.
    fun openWaiting(room: String?) { openPage("Waiting", WaitingScreen(this, room)) }
    // Historical updateBadges forwarded to the restored GlassNav badge API
    // (chats = unread, rules = pending); the settings update dot is wired to
    // UpdateCheck.hasUpdate() via the update listener in onCreate.
    fun updateBadges(unread: Int, pending: Int) {
        // Historical badge contract on the restored GlassNav: chats = unread,
        // rules = pending; the settings update dot rides setUpdateAvailable.
        nav.setBadges(unread, pending)
    }

    private fun closePage(): Boolean {
        val state = pageStack.pollLast() ?: return false
        (content.getChildAt(0) as? Screen)?.onHide()
        content.removeAllViews()
        content.addView(state.screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // A popped route can be a tab ROOT (openRoom pushRoutes the tab root
        // it was opened over). Keep the invariant currentPage == null at a
        // tab root: only non-tab screens count as a pushed page.
        currentPage = state.screen.takeUnless { tabScreens.containsValue(it) }
        roomScreen = state.room
        headerTitle.text = state.title
        headerBack.visibility = state.backVisible
        headerSearch.visibility = state.searchVisible
        setNavVisible(state.navVisible)
        state.screen.onShow()
        return true
    }

    private fun showSetup(message: String?) {
        pendingOpenRoomId = null
        displayedRoom = null
        // Session reset drops retained stale-session actions (I2 focused
        // check): a pending picker result or an open room-tools dialog must
        // not survive into the new session.
        pickImageCallback = null
        transientDialog?.dismiss()
        transientDialog = null
        setNavVisible(View.GONE)
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
        setNavVisible(View.VISIBLE)
        header.visibility = View.VISIBLE
        content.removeAllViews()
        tabScreens.clear()
        showTab(R.id.nav_chats)
    }

    fun onEnrolled() {
        // RECOVERED Nov 24, 2026 (operator Nov 15 readback, pre-checklist
        // signature): the release renewed features/session before showing main.
        scope.launch {
            try { api.renewOnOpen() }
            catch (e: SessionEndedException) { onSessionEnded(e); return@launch }
            catch (_: Exception) { snack(window.decorView, "Connected. Feature status couldn't refresh yet.") }
            if (!isForeground) return@launch
            showMain()
            UpdateCheck.checkInBackground(scope)
        }
    }

    fun onSessionEnded(e: SessionEndedException) {
        // Attributed invalidating teardown (I2): a stale response from before
        // sign-out/re-enroll must not tear down the NEW session, and the
        // teardown itself bumps the generation under stateLock so in-flight
        // old-generation responses can no longer restore the cookie.
        if (!session.endSession(e.opGeneration)) return
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
            R.id.nav_settings -> SettingsScreen(this)
            else -> RoomsScreen(this)
        }
    }

    private fun showTab(id: Int) {
        currentTab = id
        currentPage = null
        displayedRoom = null
        pageStack.clear()
        val screen = tabFor(id)
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerBack.visibility = View.GONE
        // private12 MainActivity.d0: header search shows on the rooms and
        // files tabs only; the title is the app name on the rooms tab.
        headerSearch.visibility = if (id == R.id.nav_chats || id == R.id.nav_files) View.VISIBLE else View.GONE
        headerTitle.text = when (id) {
            R.id.nav_files -> "Files"
            R.id.nav_rules -> "Rules"
            R.id.nav_settings -> "Settings"
            else -> "HomeRoom"
        }
        screen.onShow()
    }

    // RECONSTRUCTED: the release persists the open room ("last_room_id") and
    // opens at an event by setting the room anchor before showing the room.
    fun openRoomAt(room: Room, eventId: String) {
        session.setAnchor(room.id, eventId)
        openRoom(room)
    }

    // RECONSTRUCTED Nov 24 from the exact private12 decompile: the release's
    // openRoom pushes the current route first (the same pushRoute openPage and
    // openWaiting use), so Back from a room returns to whatever was beneath it
    // (Waiting, a page, or the tab root). The earlier reconstruction cleared
    // the page stack here, which dropped the Waiting route and sent Back to
    // the Chats tab instead.
    fun openRoom(room: Room) {
        session.lastRoomId = room.id
        displayedRoom = room
        val current = content.getChildAt(0) as? Screen
        if (current != null) {
            current.onHide()
            pageStack.addLast(PageState(current, headerTitle.text.toString(), headerBack.visibility, nav.visibility, headerSearch.visibility, roomScreen))
        }
        val screen = RoomScreen(this, room)
        roomScreen = screen
        currentPage = null
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        headerBack.visibility = View.VISIBLE
        headerTitle.text = room.name
        // Release: the header search stays visible in a room, scoped to it.
        headerSearch.visibility = View.VISIBLE
        setNavVisible(View.GONE)
        screen.onShow()
    }

    private fun closeRoom() {
        val closing = roomScreen
        roomScreen = null
        displayedRoom = null
        closing?.onHide()
        setNavVisible(View.VISIBLE)
        showTab(currentTab)
    }

    fun cacheRooms(rooms: List<Room>) {
        roomCache = rooms
        tryOpenPendingRoom()
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
        // Capture-under-lock (I2, Nov 24): backgrounding drops the retained
        // backdrop buffers; AppLock.onPause re-locks so the cover - and the
        // capture block - are up before the next resume. The callback review
        // found this call missing: authenticated stayed true across pauses.
        captureSuspended = true
        if (::nav.isInitialized) nav.invalidateBackdrop()
        appLock.onPause()
    }

    override fun onResume() {
        super.onResume()
        // RECOVERED Nov 24, 2026 (operator Nov 15 readback): the release's
        // onResume applied privacy, gated on the app lock, then resumed
        // content; resumeContent renewed features/session when enrolled.
        captureSuspended = false
        applyPrivacy()
        if (appLock.gate()) return
        refreshNavBackdrop()
        resumeContent()
    }

    // Historical resumeContent (Nov 15 readback): poller, visible screen poll,
    // enrolled renew. Its live-updates/server-bar helpers (startLiveUpdates/
    // updateServerBar) are NOT in this tree (still unrecovered).
    // I2 review fix: also invoked from the app-lock unlock callback below -
    // with the restored gate, a locked resume returns before starting the
    // poller/renew, and the previously no-op callback would have stalled them
    // until the next onResume.
    private fun resumeContent() {
        isForeground = true
        startPoller()
        val screen = roomScreen ?: tabScreens[currentTab]
        screen?.poll()
        if (session.enrolled) {
            scope.launch {
                try { api.renewOnOpen() }
                catch (e: SessionEndedException) { onSessionEnded(e) }
                catch (_: Exception) { }
            }
        }
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

    // private12 parity (K0/A3 case 2 via L.H): the in-room "Attach photo or
    // file" entry opens the picker with */*, not image/*.
    fun pickFile(callback: (android.net.Uri?) -> Unit) {
        pickImageCallback = callback
        runCatching { pickImageLauncher.launch("*/*") }.onFailure { pickImageCallback = null; callback(null) }
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
    val appLock: AppLock by lazy { AppLock(this) { resumeContent(); drainAfterUnlock() } }

    fun applyPrivacy() {
        if (PrivacyPrefs.hideRecents(this)) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE)
            // Drop anything captured before the flag went on.
            invalidateNavBackdrop()
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            refreshNavBackdrop()
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
            catch (e: SessionEndedException) { onSessionEnded(e) }
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

package app.homeroom

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
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

    @Volatile
    var pendingOpenRoomId: String? = null

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
    private var pollerJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
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
        header.addView(headerBack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
                if (roomScreen != null) closeRoom()
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
            UpdateCheck.checkInBackground(this, scope, session)
            maybeRegisterPush()
            handleRoomIntent(intent)
        } else {
            showSetup(null)
        }
        startPoller()
    }

    private fun showSetup(message: String?) {
        pollerJob?.cancel()
        pollerJob = null
        nav.visibility = View.GONE
        header.visibility = View.GONE
        roomScreen = null
        tabScreens.clear()
        val setup = SetupScreen(this)
        if (message != null) {
            setup.post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        content.removeAllViews()
        content.addView(setup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        UpdateCheck.checkInBackground(this, scope, session)
        maybeRegisterPush()
        requestNotificationPermission()
        startPoller()
    }

    fun onSessionEnded(reason: String?) {
        // The server deletes this device's push registration when a session
        // ends, so wipe the whole session (keeps the server URL) to force a
        // fresh push registration after re-enroll.
        session.clear()
        api = ApiClient(session)
        cancelPushState()
        showSetup("Session ended. Ask for a new connection link.")
    }

    fun onSignedOut() {
        api = ApiClient(session)
        cancelPushState()
        showSetup(null)
    }

    private fun cancelPushState() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancelAll()
        androidx.work.WorkManager.getInstance(this)
            .cancelAllWorkByTag(HomeRoomMessagingService.WORK_TAG)
        PushMessageStore.clearAll(this)
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
        val screen = tabFor(id)
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

    fun openRoom(room: Room) {
        clearRoomNotification(room.id)
        val screen = RoomScreen(this, room)
        roomScreen = screen
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            while (isActive) {
                delay(4000)
                val screen = roomScreen ?: tabScreens[currentTab]
                screen?.poll()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        pollerJob?.cancel()
        pollerJob = null
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        startPoller()
        val screen = roomScreen ?: tabScreens[currentTab]
        screen?.poll()
        // FCM collapse keys are capped per device and event ids are only a
        // hint, so reopening always reconciles every room's unread state
        // from the server, not just the room a push pointed at.
        if (session.enrolled) {
            (tabScreens[R.id.nav_chats] as? RoomsScreen)?.load(showSpinner = false)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleRoomIntent(intent)
    }

    private fun handleRoomIntent(intent: android.content.Intent?) {
        val roomId = intent?.getStringExtra(HomeRoomMessagingService.EXTRA_ROOM_ID) ?: return
        intent.removeExtra(HomeRoomMessagingService.EXTRA_ROOM_ID)
        if (!session.enrolled) return
        pendingOpenRoomId = roomId
        roomScreen?.let { closeRoom() }
        nav.selectedItemId = R.id.nav_chats
        (tabScreens[R.id.nav_chats] as? RoomsScreen)?.load(showSpinner = true)
    }

    fun clearRoomNotification(roomId: String) {
        // Under the per-room lock: an in-flight worker can't append and
        // re-post a just-read message after this clears.
        val lock = HomeRoomMessagingService.roomLocks.getOrPut(roomId) { Any() }
        synchronized(lock) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(roomId, HomeRoomMessagingService.ID_MESSAGES)
            // Read messages must not linger in the next notification stack.
            PushMessageStore.clearRoom(this, roomId)
        }
        HomeRoomMessagingService.cancelSummaryIfEmpty(this)
    }

    fun openPendingRoomIfLoaded() {
        val roomId = pendingOpenRoomId ?: return
        val room = roomCache.firstOrNull { it.id == roomId } ?: return
        pendingOpenRoomId = null
        openRoom(room)
    }

    fun maybeRegisterPush() {
        PushRegistration.refreshToken(scope, session, api)
        PushRegistration.register(scope, session, api)
    }

    fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        androidx.core.app.ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41
        )
    }

    companion object {
        @Volatile
        var isForeground: Boolean = false
    }
}

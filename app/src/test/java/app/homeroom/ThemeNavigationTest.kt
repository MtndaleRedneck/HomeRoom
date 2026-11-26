package app.homeroom

import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Cold-start tab contract (review MUST, Nov 25):
// MainActivity.showMain drives tab selection through the GlassNav
// selectedItemId setter, so the setter must fire the choose callback (which
// runs showTab) even when the assigned value equals the initial default -
// otherwise a cold start would show no tab content. Property initialization
// must NOT fire it, or construction alone would double-show a tab.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeNavigationTest {

    private fun seedEnrollment() {
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("homeroom_session", 0).edit()
            .putString("server_url", "https://smoke.invalid")
            .putString("cookie", "smoke-cookie").commit()
    }

    @org.junit.After fun clearSeededEnrollment() {
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("homeroom_session", 0).edit().clear().commit()
    }

    private fun contentOf(activity: MainActivity): FrameLayout =
        activity.javaClass.getDeclaredField("content").apply { isAccessible = true }
            .get(activity) as FrameLayout

    @Test fun navSetterFiresChooseOnSameValue() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val chosen = mutableListOf<Int>()
        val nav = GlassNav(activity, FrameLayout(activity)) { id -> chosen.add(id) }
        assertTrue("construction must not choose a tab", chosen.isEmpty())
        nav.selectedItemId = R.id.nav_chats // same value as the initial default
        assertEquals(listOf(R.id.nav_chats), chosen)
        nav.selectedItemId = R.id.nav_settings
        assertEquals(listOf(R.id.nav_chats, R.id.nav_settings), chosen)
    }

    @Test fun coldStartShowsChatsTabContent() {
        seedEnrollment()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val content = contentOf(controller.get())
        assertTrue("cold start must show tab content", content.childCount > 0)
        assertTrue("cold start must open on the Chats tab", content.getChildAt(0) is RoomsScreen)
        controller.pause().stop().destroy()
    }

}

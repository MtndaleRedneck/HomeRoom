package app.homeroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w396dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V15NativeTest {
    private fun render(view:View,name:String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(396,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,396,800)
        val image=Bitmap.createBitmap(396,800,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(C.bg);view.draw(c)
        File(System.getProperty("homeroom.previewDir")).apply {mkdirs()}.resolve("v15-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun setupStatusAndPrivacyScreens() {
        val app=RuntimeEnvironment.getApplication()
        Session(app).clear();PrivacyPrefs.set(app,"lock",false)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val host=controller.get()
        render(SetupScreen(host),"setup")
        val status=StatusScreen(host);status.onShow();render(status,"status")
        val privacy=PrivacyScreen(host);privacy.onShow();render(privacy,"privacy")
        val config=host.resources.configuration;config.fontScale=1.5f
        @Suppress("DEPRECATION") host.resources.updateConfiguration(config,host.resources.displayMetrics)
        val large=PrivacyScreen(host);large.onShow();render(large,"privacy-large")
        controller.pause().stop().destroy()
    }
    @Test fun privacyIsOptionalAndLockIsNotSavedUnlocked() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_privacy",0).edit().clear().commit()
        assertFalse(PrivacyPrefs.locked(app));assertFalse(PrivacyPrefs.hideRecents(app))
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val host=controller.get()
        PrivacyPrefs.set(app,"lock",true)
        assertTrue(host.appLock.gate())
        host.appLock.onPause();assertTrue(host.appLock.gate())
        PrivacyPrefs.set(app,"hide_previews",true)
        assertEquals(android.app.Notification.VISIBILITY_SECRET,PrivacyPrefs.visibility(app))
        PrivacyPrefs.set(app,"lock",false)
        controller.pause().stop().destroy()
        app.getSharedPreferences("app_privacy",0).edit().clear().commit()
    }
}
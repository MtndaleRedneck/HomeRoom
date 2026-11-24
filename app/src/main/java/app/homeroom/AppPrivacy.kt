package app.homeroom

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts

object PrivacyPrefs {
    private fun prefs(c:Context)=c.getSharedPreferences("app_privacy",Context.MODE_PRIVATE)
    fun locked(c:Context)=prefs(c).getBoolean("lock",false)
    fun hidePreviews(c:Context)=prefs(c).getBoolean("hide_previews",false)
    fun hideRecents(c:Context)=prefs(c).getBoolean("hide_recents",false)
    fun set(c:Context,key:String,value:Boolean) {prefs(c).edit().putBoolean(key,value).apply()}
    fun visibility(c:Context)=if(hidePreviews(c))android.app.Notification.VISIBILITY_SECRET else android.app.Notification.VISIBILITY_PRIVATE
}

/** An opaque activity-wide gate, never a saved 'unlocked' flag. */
class AppLock(private val host:MainActivity,private val unlocked:()->Unit) {
    private var authenticated=false
    private var authenticating=false
    private var enabling=false
    private var overlay:View?=null
    // Capture-under-lock gate (I2, Nov 24): MainActivity blocks the nav
    // backdrop capture while the cover is up - the cover is a sibling overlay,
    // not part of the captured column.
    val coverShowing:Boolean get()=overlay!=null
    private var signal:android.os.CancellationSignal?=null
    private val pin=host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        authenticating=false
        if(result.resultCode==Activity.RESULT_OK)success() else show()
    }
    fun secureAvailable()=(host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    fun onPause() {
        if(!authenticating) {authenticated=false;if(PrivacyPrefs.locked(host))show()}
    }
    fun gate():Boolean {
        if(!PrivacyPrefs.locked(host) || authenticated)return false
        show();return true
    }
    fun enable() {
        if(!secureAvailable()) {snack(host.window.decorView,"Set a phone PIN or fingerprint first.");return}
        enabling=true;show();authenticate()
    }
    fun disable() {PrivacyPrefs.set(host,"lock",false);authenticated=false;remove();unlocked()}
    fun show() {
        if(overlay!=null)return
        val cover=FrameLayout(host).apply {setBackgroundColor(C.bg);isClickable=true;isFocusable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES}
        val col=column(host).apply {setPadding(host.dp(24),host.dp(24),host.dp(24),host.dp(24));gravity=Gravity.CENTER}
        col.addView(text(host,"HomeRoom is locked",24f,C.text,true))
        col.addView(text(host,"Unlock with your phone's fingerprint or PIN. Nothing is sent.",15f,C.muted))
        col.addView(button(host,"Unlock",true) {authenticate()})
        cover.addView(col,FrameLayout.LayoutParams(-1,-1))
        (host.window.decorView as ViewGroup).addView(cover,ViewGroup.LayoutParams(-1,-1));overlay=cover
        host.invalidateNavBackdrop()
        cover.requestFocus()
    }
    fun authenticate() {
        if(authenticating)return
        if(!secureAvailable()) {snack(host.window.decorView,"Phone security is unavailable. HomeRoom stays locked.");return}
        authenticating=true
        if(Build.VERSION.SDK_INT>=28) {
            val builder=android.hardware.biometrics.BiometricPrompt.Builder(host).setTitle("Unlock HomeRoom")
            if(Build.VERSION.SDK_INT>=29)builder.setDeviceCredentialAllowed(true)
            else builder.setNegativeButton("Use PIN",host.mainExecutor) {_,_->authenticating=false;launchPin()}
            signal=android.os.CancellationSignal()
            builder.build().authenticate(signal!!,host.mainExecutor,object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {authenticating=false;success()}
                override fun onAuthenticationError(code:Int,message:CharSequence) {
                    authenticating=false
                    if(code==android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || code==android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT)launchPin()
                }
            })
        } else launchPin()
    }
    @Suppress("DEPRECATION") private fun launchPin() {
        val intent=(host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).createConfirmDeviceCredentialIntent("Unlock HomeRoom","Confirm your phone PIN, pattern or password")
        if(intent==null) {authenticating=false;return}
        authenticating=true;pin.launch(intent)
    }
    private fun success() {
        if(enabling) {PrivacyPrefs.set(host,"lock",true);enabling=false}
        authenticated=true;remove();unlocked()
    }
    private fun remove() {overlay?.let {(it.parent as? ViewGroup)?.removeView(it)};overlay=null;host.refreshNavBackdrop()}
}

class PrivacyScreen(context:Context):Screen(context) {
    private val body=column(context).apply {setPadding(dp(16),dp(12),dp(16),dp(24))}
    init {addView(android.widget.ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {render()}
    private fun render() {
        body.removeAllViews()
        fun setting(title:String,value:Boolean,key:String,description:String) {
            body.addView(text(context,description,14f,C.muted))
            body.addView(button(context,title+if(value)": On" else ": Off",false) {
                PrivacyPrefs.set(context,key,!value);host.applyPrivacy();render()
            })
        }
        body.addView(text(context,"Privacy",22f,C.text,true))
        body.addView(text(context,"App lock is optional and off by default. It locks whenever you leave HomeRoom. Notification replies are unavailable while app lock is enabled.",14f,C.muted))
        body.addView(button(context,if(PrivacyPrefs.locked(context))"Turn off app lock" else "Turn on fingerprint or PIN lock",false) {
            if(PrivacyPrefs.locked(context))host.appLock.disable() else host.appLock.enable()
            render()
        })
        setting("Hide lock-screen alerts",PrivacyPrefs.hidePreviews(context),"hide_previews","Hide HomeRoom notifications on the lock screen. Android may already hide their contents.")
        setting("Hide in recent apps",PrivacyPrefs.hideRecents(context),"hide_recents","Hide app content in recent apps and block screenshots/screen recording while enabled.")
    }
}
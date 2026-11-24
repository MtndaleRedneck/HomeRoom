package app.homeroom

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.launch

/** What the owner's own picture looks like, shared between Settings and this page. */
object OwnerProfileState {
    @Volatile var avatar:AiAvatar?=null
}

/** Settings > your name card: change what you show up as, and your picture. Both are presentation only. */
class ProfileScreen(context:Context):Screen(context) {
    private val body=column(context)
    private var busy=false
    private var loaded=false
    private var shownName=""
    private var typedName:String?=null
    init {body.setPadding(dp(16),dp(12),dp(16),dp(24));addView(ScrollView(context).apply {addView(body)},LayoutParams(-1,-1))}
    override fun onShow() {
        shownName=host.session.ownerName ?: ""
        render()
        screenScope.launch {
            try {val (name,avatar)=host.api.ownerProfile();shownName=name;OwnerProfileState.avatar=avatar;loaded=true;render()}
            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {snack(this@ProfileScreen,"Couldn't load your profile.")}
        }
    }
    private fun render() {
        body.removeAllViews()
        val card=column(context).apply {background=rounded(C.surface,dp(20).toFloat());setPadding(dp(16),dp(16),dp(16),dp(16))}
        val pic=Avatars.view(context,screenScope,host.api,shownName.ifBlank {"You"},C.owner,96,OwnerProfileState.avatar).apply {
            contentDescription="Change your picture";setOnClickListener {host.pickImage {uri -> if(uri!=null) setPicture(uri)}}}
        card.addView(pic,LinearLayout.LayoutParams(dp(96),dp(96)).apply {gravity=Gravity.CENTER_HORIZONTAL})
        card.addView(text(context,"Change picture",14f,C.accent,true).apply {gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(4));setOnClickListener {host.pickImage {uri -> if(uri!=null) setPicture(uri)}}},LinearLayout.LayoutParams(-1,-2))
        if(OwnerProfileState.avatar!=null) card.addView(text(context,"Remove picture",13.5f,C.danger,true).apply {gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(4));setOnClickListener {setPicture(null)}},LinearLayout.LayoutParams(-1,-2))
        card.addView(text(context,"Name",13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
        val name=input(context,"Your name").apply {
            background=rounded(C.surfaceAlt,dp(12).toFloat());setPadding(dp(12),dp(10),dp(12),dp(10));setText(typedName ?: shownName);maxLines=1
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,d:Int) {}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,d:Int) {}
                override fun afterTextChanged(s:android.text.Editable?) {typedName=s.toString()}
            })
        }
        card.addView(name,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        card.addView(text(context,"This is the name your AIs and rooms show for you. It doesn't change how you sign in.",12.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        card.addView(button(context,"Save name",true) {saveName(name.text.toString())},LinearLayout.LayoutParams(-1,dp(44)).apply {topMargin=dp(12)})
        body.addView(card,LinearLayout.LayoutParams(-1,-2))
    }
    private fun saveName(raw:String) {
        val value=raw.trim()
        if(!validName(value)) {snack(this,"Use 1 to 80 characters, with no line breaks.");return}
        if(busy)return
        busy=true
        screenScope.launch {
            try {
                val saved=host.api.setOwnerName(value)
                shownName=saved;typedName=null;host.session.ownerName=saved
                snack(this@ProfileScreen,"Name saved.");render()
            }
            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(e:ApiException) {snack(this@ProfileScreen,if(e.status==400) "That name can't be used." else "Couldn't save the name.")}
            catch(_:Exception) {snack(this@ProfileScreen,"Couldn't confirm. Check your name before trying again.")}
            finally {busy=false}
        }
    }
    private fun setPicture(uri:android.net.Uri?) {
        if(busy)return
        busy=true
        screenScope.launch {
            try {
                val upload=if(uri==null) null else (Avatars.prepare(context,uri) ?: run {snack(this@ProfileScreen,"That picture can't be used. Try a different one.");busy=false;return@launch})
                OwnerProfileState.avatar=host.api.setOwnerAvatar(upload)
                snack(this@ProfileScreen,if(uri==null) "Picture removed." else "Picture saved.");render()
            }
            catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(e:ApiException) {snack(this@ProfileScreen,if(e.code=="bad_avatar") "That picture can't be used. Try a different one." else "Couldn't save the picture.")}
            catch(_:Exception) {snack(this@ProfileScreen,"Couldn't confirm the picture.")}
            finally {busy=false}
        }
    }
    companion object {
        /** 1 to 80 characters after trimming, no control characters. The server checks again. */
        fun validName(v:String):Boolean = v.isNotBlank() && v.trim().length<=80 && v.none {it.isISOControl()}
    }
}
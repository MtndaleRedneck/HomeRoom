package app.homeroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.TextView
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Pictures for the owner and for AIs. They are private to the HomeRoom server and fetched with the app's own session. */
// RECONSTRUCTED: avatar upload payload; the release posts
// {"image": {"mime_type": "image/jpeg", "base64": ...}} (or image null to remove).
data class AvatarUpload(val mimeType: String, val base64: String)

object Avatars {
    private const val MAX_SIDE=512
    private const val MAX_BYTES=200*1024
    private val cache=LruCache<String,Bitmap>(24)

    fun key(a:AiAvatar)=a.sha256 ?: a.url

    /** Shrinks a chosen picture so the upload stays well under the server's limits. Returns null if it can't be read as an image. */
    suspend fun prepare(context:Context,uri:Uri):AvatarUpload? = withContext(Dispatchers.IO) {
        try {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
            if(bounds.outWidth<=0 || bounds.outHeight<=0) return@withContext null
            val sample=sampleSize(bounds.outWidth,bounds.outHeight)
            val opts=BitmapFactory.Options().apply {inSampleSize=sample}
            val decoded=context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,opts)} ?: return@withContext null
            val scale=minOf(1f,MAX_SIDE.toFloat()/maxOf(decoded.width,decoded.height))
            val bitmap=if(scale<1f) Bitmap.createScaledBitmap(decoded,maxOf(1,(decoded.width*scale).toInt()),maxOf(1,(decoded.height*scale).toInt()),true) else decoded
            var quality=90
            while(true) {
                val out=ByteArrayOutputStream()
                // JPEG has no transparency; paint on white so transparent areas don't turn black.
                val flat=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888).also {android.graphics.Canvas(it).apply {drawColor(android.graphics.Color.WHITE);drawBitmap(bitmap,0f,0f,null)}}
                flat.compress(Bitmap.CompressFormat.JPEG,quality,out)
                if(out.size()<=MAX_BYTES || quality<=40) return@withContext if(out.size()<=MAX_BYTES) AvatarUpload("image/jpeg",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)) else null
                quality-=10
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch(e:Exception) {null}
    }

    /** Power-of-two decode step so the larger side lands between MAX_SIDE and 2x MAX_SIDE. */
    internal fun sampleSize(w:Int,h:Int):Int {
        var s=1
        while(maxOf(w,h)/(s*2)>=MAX_SIDE) s*=2
        return s
    }

    // RECONSTRUCTED: letter-initial avatar shown until (or unless) a picture loads.
    private fun avatar(context: Context, name: String, color: Int, size: Int): View =
        TextView(context).apply {
            text = name.trim().firstOrNull()?.uppercase() ?: "?"
            textSize = size / 2.6f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(color, size / 2f)
        }

    /** A round avatar: the initial first, then the picture once it has loaded. */
    fun view(context:Context,scope:CoroutineScope,api:ApiClient,name:String,color:Int,size:Int,picture:AiAvatar?):FrameLayout {
        val box=FrameLayout(context)
        box.addView(avatar(context,name,color,size),FrameLayout.LayoutParams(-1,-1))
        if(picture!=null) {
            val k=key(picture)
            fun show(bitmap:Bitmap) {
                val image=ImageView(context).apply {
                    scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(bitmap)
                    outlineProvider=object:ViewOutlineProvider() {override fun getOutline(v:View,o:android.graphics.Outline) {o.setOval(0,0,v.width,v.height)}}
                    clipToOutline=true;contentDescription=name
                }
                box.addView(image,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
            }
            val cached=cache.get(k)
            if(cached!=null) show(cached) else scope.launch {
                try {
                    val bytes=api.avatarBytes(picture.url)
                    val bitmap=withContext(Dispatchers.Default) {BitmapFactory.decodeByteArray(bytes,0,bytes.size)} ?: return@launch
                    cache.put(k,bitmap);show(bitmap)
                } catch(_:Exception) {}
            }
        }
        return box
    }
}
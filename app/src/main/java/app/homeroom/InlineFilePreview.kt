package app.homeroom

import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object InlineFilePreview {
    fun show(host:MainActivity,file:RoomFile,scope:CoroutineScope) {
        scope.launch {
            try {
                val(bytes,_)=host.api.filePreview(file.id)
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                check(bounds.outWidth>0 && bounds.outHeight>0)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample}) ?: throw IllegalStateException()
                val image=ZoomImageView(host).apply {setImageBitmap(bitmap)}
                val dialog=android.app.Dialog(host,android.R.style.Theme_Material_NoActionBar_Fullscreen)
                val col=column(host)
                col.addView(button(host,"Close preview",false) {dialog.dismiss()})
                col.addView(text(host,file.name,15f,C.text))
                col.addView(image,LinearLayout.LayoutParams(-1,0,1f))
                dialog.setContentView(col);dialog.show()
            } catch(e:SessionEndedException) {host.onSessionEnded(e.reason)} catch(_:Exception) {android.widget.Toast.makeText(host,"Couldn't load the image.",android.widget.Toast.LENGTH_LONG).show()}
        }
    }
}
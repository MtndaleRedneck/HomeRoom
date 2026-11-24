package app.homeroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

object NotificationPeople {
    fun person(name:String,actor:String):Person {
        val icon=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(icon)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=personColor(actor)}
        canvas.drawCircle(32f,32f,32f,paint)
        paint.color=C.bg;paint.textSize=30f;paint.textAlign=Paint.Align.CENTER
        canvas.drawText(name.take(1).uppercase(),32f,32f-(paint.ascent()+paint.descent())/2,paint)
        return Person.Builder().setName(name).setKey(actor).setIcon(IconCompat.createWithBitmap(icon)).build()
    }
    fun shortcut(context:Context,room:String,name:String,intent:android.content.Intent,person:Person):String {
        val id="room:"+room
        val shortcut=androidx.core.content.pm.ShortcutInfoCompat.Builder(context,id).setShortLabel(name.ifBlank {"HomeRoom"}).setLongLived(true).setPerson(person).setIntent(intent.setAction(android.content.Intent.ACTION_VIEW)).build()
        androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(context,shortcut)
        return id
    }
}
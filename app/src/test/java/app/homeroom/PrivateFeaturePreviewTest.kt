package app.homeroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Test-only design sketches. Nothing here is included in the application APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateFeaturePreviewTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun label(value:String,size:Float=15f,color:Int=C.text,bold:Boolean=false)=text(context,value,size,color,bold)
    private fun frame(title:String):LinearLayout=column(context).apply {
        setBackgroundColor(C.bg);setPadding(16,32,16,24)
        addView(label("PRIVATE DESIGN PREVIEW",12f,C.amber,true),LinearLayout.LayoutParams(-1,32))
        addView(label(title,24f,C.text,true),LinearLayout.LayoutParams(-1,56))
        addView(label("Not connected. Not included in the app.",13f,C.muted),LinearLayout.LayoutParams(-1,40))
    }
    private fun field(hint:String,value:String="",lines:Int=1)=input(context,hint).apply {
        setText(value);minLines=lines;maxLines=8;background=rounded(C.surfaceAlt,16f);setPadding(14,12,14,12)
    }
    private fun actions(parent:LinearLayout,secondary:String,primary:String) {
        parent.addView(LinearLayout(context).apply {
            gravity=Gravity.END
            addView(button(context,secondary,false) {},LinearLayout.LayoutParams(-2,48).apply {rightMargin=8})
            addView(button(context,primary) {}.apply {isEnabled=false;alpha=.55f},LinearLayout.LayoutParams(-2,48))
        },LinearLayout.LayoutParams(-1,48).apply {topMargin=16})
    }
    private fun render(view:View,name:String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));view.layout(0,0,360,780)
        val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        (System.getProperty("hr.previewDir")?.let {File(it)} ?: File("build/previews").apply {mkdirs()}).resolve("private-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun unavailableFlowsRemainTestOnly() {
        val edit=frame("Edit a rule")
        val sheet=column(context).apply {background=rounded(C.surface,20f);setPadding(16,20,16,20)}
        sheet.addView(label("What should Tab change?",16f,C.text,true))
        sheet.addView(field("Your notes for Tab","",4),LinearLayout.LayoutParams(-1,160).apply {topMargin=16})
        sheet.addView(label("Notes return to the proposing AI for review.",13f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=12})
        actions(sheet,"Cancel","Send back");edit.addView(sheet,LinearLayout.LayoutParams(-1,-2).apply {topMargin=24});render(edit,"edit")
        val room=frame("New room")
        val card=column(context).apply {background=rounded(C.surface,20f);setPadding(16,20,16,20)}
        card.addView(label("Name",13f,C.muted,true));card.addView(field("Room name"),LinearLayout.LayoutParams(-1,52).apply {topMargin=8})
        card.addView(label("0/40",12f,C.muted).apply {gravity=Gravity.END})
        card.addView(label("Who's in it",15f,C.text,true),LinearLayout.LayoutParams(-1,48))
        for((name,color) in listOf("Tab" to C.teal,"Instinct" to C.violet))card.addView(CheckBox(context).apply {text=name;isChecked=true;setTextColor(color);buttonTintList=android.content.res.ColorStateList.valueOf(C.accent)},LinearLayout.LayoutParams(-1,48))
        actions(card,"Cancel","Add");room.addView(card,LinearLayout.LayoutParams(-1,-2).apply {topMargin=24});render(room,"add-room")
        val guide=frame("AI guide")
        guide.addView(label("Version and read confirmations will come from the server.",14.5f,C.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=12})
        val status=column(context).apply {background=rounded(C.surface,20f);setPadding(16,16,16,16)}
        for(name in listOf("Tab","Instinct"))status.addView(label("$name · No confirmation recorded",14f,C.muted),LinearLayout.LayoutParams(-1,40))
        guide.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=20})
        guide.addView(label("Guide text",16f,C.text,true),LinearLayout.LayoutParams(-1,56).apply {topMargin=16})
        guide.addView(label("Read-only text will appear here when the server supports the guide. This preview does not claim a published version or any AI acknowledgment.",14.5f,C.text).apply {setTextIsSelectable(true)})
        render(guide,"guide")
    }
}
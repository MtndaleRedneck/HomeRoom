package app.homeroom

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.launch

/** Files are server facts. Count labels appear only after all pages are read. */
class FilesScreen(context:Context):Screen(context) {
    private val root=column(context)
    private val chips=LinearLayout(context)
    private val body=column(context)
    private val query=input(context,"Search file names")
    private val files=mutableListOf<RoomFile>()
    private val note=text(context,"Loading files",15f,C.muted)
    private var rooms=listOf<Room>()
    private var selected:Room?=null
    private var loading=false
    private var generation=0
    init {
        root.addView(text(context,"What you and your AIs shared, by room",13f,C.muted).apply { setPadding(dp(16),0,dp(16),dp(12)) })
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;addView(chips) })
        query.visibility=View.GONE;query.setSingleLine();root.addView(query,LinearLayout.LayoutParams(-1,dp(48)))
        query.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { render() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        body.setPadding(dp(16),dp(8),dp(16),dp(110));body.addView(note)
        root.addView(ScrollView(context).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f));addView(root,LayoutParams(-1,-1))
    }
    fun showSearch() { query.visibility=View.VISIBLE;query.requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(query,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    override fun onShow() {
        host.scope.launch {
            try {
                rooms=host.cachedRooms().ifEmpty { host.api.roomRoster().rooms }
                if(selected==null)selected=rooms.firstOrNull { it.id==host.session.lastRoomId } ?: rooms.firstOrNull()
                buildChips();selected?.let { load(it) } ?: run { note.text="Nothing shared in this room yet." }
            } catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) { note.text="Couldn't load files. Tap to retry.";note.setOnClickListener { onShow() } }
        }
    }
    private fun buildChips() {
        chips.removeAllViews();chips.setPadding(dp(16),0,dp(16),dp(8))
        rooms.forEach { room ->
            val chip=text(context,room.name,14f,if(room.id==selected?.id) android.graphics.Color.WHITE else C.text,true).apply { gravity=Gravity.CENTER;setPadding(dp(14),0,dp(14),0);background=rounded(if(room.id==selected?.id) C.accent else C.surfaceAlt,dp(17).toFloat());setOnClickListener { if(room.id!=selected?.id) {selected=room;buildChips();load(room)} } }
            chips.addView(chip,LinearLayout.LayoutParams(-2,dp(34)).apply { rightMargin=dp(8) })
        }
    }
    private fun load(room:Room) {
        val token=++generation
        loading=true;files.clear();body.removeAllViews();note.text="Loading files";body.addView(note)
        host.scope.launch {
            try {
                val all=mutableListOf<RoomFile>();var cursor:String?=null;val seen=mutableSetOf<String>()
                do {
                    val page=host.api.filePage(room.id,cursor)
                    if(token!=generation)return@launch
                    all.addAll(page.files)
                    cursor=if(page.hasMore) page.cursor ?: throw ApiException(0,"invalid_response_cursor") else null
                    if(cursor!=null && !seen.add(cursor))throw ApiException(0,"invalid_response_cursor")
                } while(cursor!=null)
                files.addAll(all.distinctBy { it.id.ifBlank { it.url.orEmpty() } });render()
            } catch(e:SessionEndedException) { host.onSessionEnded(e) }
            catch(_:Exception) { if(token==generation) { note.text="Couldn't load files. Tap to retry.";note.setOnClickListener { load(room) } } }
            finally { if(token==generation)loading=false }
        }
    }
    private fun label(value:String) { body.addView(text(context,value,13f,C.muted,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16);bottomMargin=dp(8) }) }
    private fun render() {
        body.removeAllViews()
        val filtered=files.filter { (it.title ?: it.name).contains(query.text.toString(),true) }
        val images=filtered.filter { it.isImage }
        if(images.isNotEmpty()) {
            label("Images · ${images.size}")
            images.chunked(3).forEach { row ->
                val grid=LinearLayout(context)
                for(file in row) {
                    val tile=FrameLayout(context).apply { background=rounded(C.surfaceAlt,dp(14).toFloat());clipToOutline=true;setOnClickListener { preview(file) };file.eventId?.let { setOnLongClickListener { showInChat(file);true } } }
                    val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=file.name }
                    tile.addView(image,FrameLayout.LayoutParams(-1,-1))
                    val dot=View(context).apply { background=rounded(personColor(file.uploader),dp(4).toFloat(),C.bg,dp(1.5f)) }
                    tile.addView(dot,FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP or Gravity.RIGHT).apply {topMargin=dp(6);rightMargin=dp(6)})
                    grid.addView(tile,LinearLayout.LayoutParams(0,dp(104),1f).apply {rightMargin=dp(8)})
                    val current=generation
                    host.scope.launch {
                        try {
                            val(bytes,_)=host.api.filePreview(file.id)
                            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                            var scale=1;while(options.outWidth/scale>600 || options.outHeight/scale>600)scale*=2
                            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=scale})
                            if(current==generation)image.setImageBitmap(bitmap)
                        } catch(e:SessionEndedException) { host.onSessionEnded(e) } catch(_:Exception) {image.contentDescription="Image unavailable. Tap to retry."}
                    }
                }
                repeat(3-row.size) { grid.addView(View(context),LinearLayout.LayoutParams(0,dp(104),1f).apply { rightMargin=dp(8) }) }
                body.addView(grid,LinearLayout.LayoutParams(-1,dp(104)).apply { bottomMargin=dp(8) })
            }
        }
        val others=filtered.filter { !it.isImage }
        if(others.isNotEmpty()) {
            label("Other files · ${others.size}")
            val card=column(context).apply {background=rounded(C.surface,dp(20).toFloat());clipToOutline=true}
            others.forEachIndexed { index,file ->
                val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);setOnClickListener { open(file) };file.eventId?.let { setOnLongClickListener {showInChat(file);true} } }
                val type=when {file.kind=="link" -> "↗";file.name.endsWith(".apk",true) -> "APK";file.mimeType=="application/pdf" -> "PDF";else -> "FILE"}
                val color=when(type) {"APK" -> C.green;"PDF" -> 0xffe5577a.toInt();"↗" -> C.accent;else -> C.muted}
                row.addView(text(context,type,11f,color,true).apply {gravity=Gravity.CENTER;background=rounded((color and 0x00ffffff) or 0x2e000000,dp(12).toFloat())},LinearLayout.LayoutParams(dp(40),dp(40)).apply {rightMargin=dp(12)})
                val copy=column(context)
                copy.addView(text(context,file.title ?: file.name,15.5f,C.text,true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE})
                copy.addView(text(context,(if(file.kind=="link") "link" else humanSize(file.size))+" · "+file.uploader+" · "+formatRoomTime(file.createdAt),13f,C.muted).apply {maxLines=1})
                row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(text(context,if(file.kind=="link") "↗" else "↓",24f,C.disabled).apply {gravity=Gravity.CENTER;contentDescription=if(file.kind=="link") "Open link" else "Download";setOnClickListener {open(file)}},LinearLayout.LayoutParams(dp(48),dp(48)))
                card.addView(row,LinearLayout.LayoutParams(-1,dp(68)))
                if(index<others.lastIndex) card.addView(divider(context).apply {setBackgroundColor(0xff24303c.toInt())})
            }
            body.addView(card)
        }
        if(filtered.isEmpty())body.addView(text(context,if(query.text.isNotEmpty()) "No files match." else "Nothing shared in this room yet.",15f,C.muted))
    }
    private fun open(file:RoomFile) {
        if(file.kind=="link") {
            val uri=file.url?.let { Uri.parse(it) }
            if(uri==null || uri.scheme !in listOf("https","http")) { snack(this,"This link is unavailable.");return }
            try {host.startActivity(Intent(Intent.ACTION_VIEW,uri))} catch(_:android.content.ActivityNotFoundException) {snack(this,"No browser is available to open this link.")}
        } else FileDownloads.download(host,file)
    }
    private fun showInChat(file:RoomFile) {
        AlertDialog.Builder(context).setItems(arrayOf("Show in chat")) {_,_->selected?.let { room->file.eventId?.let {host.openRoomAt(room,it)} }}.show()
    }
    private fun preview(file:RoomFile) {
        host.scope.launch {
            try {
                val(bytes,_)=host.api.filePreview(file.id)
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: throw IllegalStateException()
                val image=ZoomImageView(context).apply {setImageBitmap(bitmap);setBackgroundColor(android.graphics.Color.BLACK)}
                val dialog=android.app.Dialog(context,android.R.style.Theme_Material_NoActionBar_Fullscreen)
                val container=column(context).apply {setBackgroundColor(android.graphics.Color.BLACK)}
                val top=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
                top.addView(text(context,"‹",26f,C.text).apply {setOnClickListener {dialog.dismiss()}},LinearLayout.LayoutParams(dp(48),dp(48)))
                top.addView(text(context,file.name+"\n"+file.uploader+" · "+formatRoomTime(file.createdAt),13.5f,C.text).apply {maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
                top.addView(text(context,"↓",24f,C.text).apply {setOnClickListener {open(file)}},LinearLayout.LayoutParams(dp(48),dp(48)))
                container.addView(top);container.addView(image,LinearLayout.LayoutParams(-1,0,1f));dialog.setContentView(container);dialog.show()
            } catch(e:SessionEndedException) {host.onSessionEnded(e)} catch(_:Exception) {snack(this@FilesScreen,"Couldn't load the preview. Tap to retry.")}
        }
    }
    private fun dp(value:Float)=(value*resources.displayMetrics.density).toFloat()
    private fun humanSize(bytes:Long)=when {bytes>=1048576 -> String.format("%.1f MB",bytes/1048576.0);bytes>=1024 -> String.format("%.0f KB",bytes/1024.0);else -> "$bytes B"}
}

class ZoomImageView(context:Context):androidx.appcompat.widget.AppCompatImageView(context) {
    private var zoom=1f
    private val detector=android.view.ScaleGestureDetector(context,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d:android.view.ScaleGestureDetector):Boolean {zoom=(zoom*d.scaleFactor).coerceIn(1f,5f);scaleX=zoom;scaleY=zoom;return true}
    })
    init {scaleType=ImageView.ScaleType.FIT_CENTER}
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {detector.onTouchEvent(event);return true}
}
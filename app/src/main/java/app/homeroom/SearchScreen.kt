package app.homeroom

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchScreen(context:Context,private val roomId:String?=null):Screen(context) {
    private val query=input(context,"Search messages")
    private val hits=mutableListOf<SearchHit>()
    private val adapter=Adapter()
    private val empty=text(context,"",15f,C.muted)
    private var searchJob:Job?=null
    private var cursor:String?=null
    private var hasMore=false
    private var loading=false
    private var generation=0
    private var currentQuery=""
    init {
        val root=column(context)
        val wrap=LinearLayout(context).apply {setPadding(dp(16),dp(8),dp(16),dp(8))}
        query.setSingleLine();query.background=rounded(C.surfaceAlt,dp(24).toFloat());query.setPadding(dp(16),0,dp(16),0);query.imeOptions=EditorInfo.IME_ACTION_SEARCH
        wrap.addView(query,LinearLayout.LayoutParams(-1,dp(48)));root.addView(wrap)
        query.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
            override fun afterTextChanged(s:android.text.Editable?) {
                val q=s?.toString()?.trim().orEmpty();searchJob?.cancel();++generation
                hits.clear();adapter.notifyDataSetChanged();empty.text="";currentQuery=q;cursor=null;hasMore=false;loading=false
                if(q.length>=2)searchJob=host.scope.launch {delay(300);search(false)}
            }
        })
        query.setOnEditorActionListener {_,_,_->search(false);true}
        val list=RecyclerView(context).apply {
            layoutManager=LinearLayoutManager(context);adapter=this@SearchScreen.adapter
            addOnScrollListener(object:RecyclerView.OnScrollListener() {override fun onScrolled(rv:RecyclerView,dx:Int,dy:Int) {if(dy>0 && (layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=hits.size-4 && hasMore)search(true)}})
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));root.addView(empty);addView(root,LayoutParams(-1,-1))
    }
    override fun onShow() {query.requestFocus()}
    override fun onHide() {searchJob?.cancel()}
    private fun search(more:Boolean) {
        if(loading || currentQuery.length<2 || (more && !hasMore))return
        loading=true;val version=generation;val q=currentQuery
        host.scope.launch {
            try {
                val page=host.api.searchPage(q,roomId,if(more)cursor else null)
                val result=page.requiredArray("results").objects().map {SearchHit.from(it)}
                if(version!=generation)return@launch
                if(!more)hits.clear()
                hits.addAll(result.filter {new->hits.none {it.eventId==new.eventId}})
                cursor=page.nullableString("cursor");hasMore=page.optBoolean("has_more",false) && cursor!=null
                adapter.notifyDataSetChanged();empty.text=if(hits.isEmpty())"No messages match." else ""
            }catch(e:SessionEndedException) {host.onSessionEnded(e.reason)}
            catch(_:Exception) {if(version==generation){empty.text="Search didn't work. Tap to retry.";empty.setOnClickListener {search(more)}}}
            finally {if(version==generation)loading=false}
        }
    }
    private inner class Adapter:RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount()=hits.size
        override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int):RecyclerView.ViewHolder {
            val cell=column(context).apply {setPadding(dp(16),dp(8),dp(16),dp(8));layoutParams=RecyclerView.LayoutParams(-1,dp(64))}
            return object:RecyclerView.ViewHolder(cell) {}
        }
        override fun onBindViewHolder(holder:RecyclerView.ViewHolder,index:Int) {
            val hit=hits[index];val cell=holder.itemView as LinearLayout;cell.removeAllViews()
            val room=host.cachedRooms().firstOrNull {it.id==hit.roomId}
            cell.addView(text(context,(room?.name ?: hit.roomName)+" · "+formatRoomTime(hit.createdAt),13f,C.muted,true))
            val value=android.text.SpannableString(hit.excerpt)
            // RECONSTRUCTED: when the server omits the range, find the query in the excerpt.
            val start=if(hit.matchStart>=0) hit.matchStart else if(currentQuery.isNotEmpty()) hit.excerpt.indexOf(currentQuery,ignoreCase=true) else -1
            val length=if(hit.matchLength>0) hit.matchLength else if(start>=0) currentQuery.length else 0
            if(start>=0 && length>0 && start+length<=value.length)value.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),start,start+length,0)
            cell.addView(text(context,value,15f,C.text).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            cell.setOnClickListener {if(room!=null)host.openRoomAt(room,hit.eventId) else snack(this@SearchScreen,"This room isn't available.")}
        }
    }
}
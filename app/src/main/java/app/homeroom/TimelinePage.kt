package app.homeroom

import org.json.JSONObject

internal data class TimelinePage(val events:List<ChatEvent>,val newest:String?,val oldest:String?,val hasMore:Boolean) {
    companion object {
        fun from(o:JSONObject)=TimelinePage(o.requiredArray("events").objects().map {ChatEvent.from(it)},o.nullableString("newest"),o.nullableString("oldest"),o.optBoolean("has_more",false))
    }
}
/** Uses event-ID cursors only; never substitutes a seq into a cursor parameter. */
internal suspend fun seekFirstUnread(first:TimelinePage,unread:Long?,active:()->Boolean,fetch:suspend (String)->TimelinePage):TimelinePage {
    val events=first.events.toMutableList();var oldest=first.oldest;var more=first.hasMore
    val seen=mutableSetOf<String>()
    while(unread!=null && (events.minOfOrNull {it.seq} ?: Long.MIN_VALUE)>unread && more && active()) {
        val cursor=oldest ?: throw ApiException(0,"invalid_response_cursor")
        if(!seen.add(cursor))throw ApiException(0,"invalid_response_cursor")
        val page=fetch(cursor)
        events.addAll(page.events);oldest=page.oldest;more=page.hasMore && page.events.isNotEmpty()
    }
    return TimelinePage(events.distinctBy {it.eventId},first.newest,oldest,more)
}
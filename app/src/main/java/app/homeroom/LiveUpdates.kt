package app.homeroom

import kotlinx.coroutines.*

/** Invalidation cursor advances only after its authorized refresh completes. */
internal class LiveUpdates(private val api:ApiClient,private val refresh:suspend (List<org.json.JSONObject>?,Boolean)->Unit) {
    suspend fun run() {
        var cursor:String?=null;var failures=0;var drain=false
        while(currentCoroutineContext().isActive) {
            try {
                if(cursor==null) {
                    val bootstrap=api.heldUpdates(null,0)
                    val watermark=bootstrap.getString("next_cursor")
                    refresh(null,true)
                    cursor=watermark
                }
                val response=api.heldUpdates(cursor,if(drain)0 else 25)
                val changes=response.requiredArray("changes").objects()
                if(changes.isNotEmpty())refresh(changes,false)
                cursor=response.getString("next_cursor");drain=response.optBoolean("has_more");failures=0
                // An immediate empty response must not create a hot retry loop.
                if(changes.isEmpty())delay(250)
            }catch(e:CancellationException) {throw e}
            catch(e:SessionEndedException) {throw e}
            catch(e:ApiException) {
                if(e.status in listOf(409,410)) {cursor=null;drain=false;refresh(null,true)}
                else {failures++;delay(maxOf(e.retryAfter*1000L,backoff(failures)))}
            }catch(_:Exception) {failures++;delay(backoff(failures))}
        }
    }
    private fun backoff(attempt:Int):Long {
        val base=listOf(1000L,2000,4000,8000,16000,30000)[(attempt-1).coerceIn(0,5)]
        return base+kotlin.random.Random.nextLong(0,base/5+1)
    }
}
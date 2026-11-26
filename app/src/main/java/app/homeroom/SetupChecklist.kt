package app.homeroom

import kotlinx.coroutines.CancellationException

internal enum class SetupStep { LINK, SERVER, SIGN_IN, ROOMS, NOTIFICATIONS }
internal data class SetupCheck(val state:String="Waiting",val detail:String="",val next:String="")

/** No network side effects are retried here except reads. A link is submitted once per workflow. */
internal class SetupChecklist(savedConnection:Boolean=false) {
    val checks=SetupStep.entries.associateWith {SetupCheck()}.toMutableMap()
    var busy=false;private set
    var submitted=false;private set
    var accepted=savedConnection;private set
    var ready=false;private set
    // Actual original source (read Nov 15 12:33:36 EST): set when the saved
    // sign-in is rejected (SessionEndedException, or 401 after acceptance);
    // the final guard patch (Nov 15 12:34:15 EST) blocks re-enroll/reads.
    var ended=false;private set
    fun freshLink() {if(!busy && !accepted) {submitted=false;SetupStep.entries.filter {it!=SetupStep.NOTIFICATIONS}.forEach {checks[it]=SetupCheck()}}}
    private fun set(step:SetupStep,state:String,detail:String="",next:String="") {checks[step]=SetupCheck(state,detail,next)}
    init {if(savedConnection)set(SetupStep.LINK,"Passed","Using this phone's saved connection.")}
    fun notifications(allowed:Boolean) {set(SetupStep.NOTIFICATIONS,if(allowed)"Passed" else "Waiting",if(allowed)"Android allows alerts. Delivery has not been tested." else "Alerts are optional. You can connect without them.",if(allowed)"" else "Allow alerts, or continue without them.")}
    suspend fun connect(linkValid:Boolean,enroll:suspend (suspend ()->Unit)->Unit,resume:suspend ()->Unit,rooms:suspend ()->Unit,changed:()->Unit) {
        if(busy || ready || ended)return
        if(!accepted && !linkValid) {set(SetupStep.LINK,"Needs attention","The full HTTPS connection link is missing or invalid.","Paste a fresh full link, including the part after #.");changed();return}
        if(!accepted && submitted) {set(SetupStep.SIGN_IN,"Not confirmed","The earlier link submission may have reached the server.","Get a fresh connection link before starting again.");changed();return}
        busy=true
        set(SetupStep.LINK,"Passed",if(accepted)"Using this phone's saved connection." else "Full link checked. The private code stays hidden.")
        set(SetupStep.SERVER,"Checking","Waiting for the server to answer.")
        set(SetupStep.SIGN_IN,"Waiting")
        set(SetupStep.ROOMS,"Waiting")
        changed()
        var stage=SetupStep.SIGN_IN
        try {
            if(accepted) {resume();set(SetupStep.SERVER,"Passed","The server answered.");set(SetupStep.SIGN_IN,"Passed","Your saved sign-in was accepted.")}
            else {
                submitted=true
                enroll {
                    accepted=true
                    set(SetupStep.SERVER,"Passed","The server answered.")
                    set(SetupStep.SIGN_IN,"Checking","Link accepted. Checking the saved sign-in.")
                    changed()
                }
                set(SetupStep.SIGN_IN,"Passed","Your sign-in was accepted.")
            }
            stage=SetupStep.ROOMS
            set(SetupStep.ROOMS,"Checking","Loading the current room list.");changed()
            rooms()
            set(SetupStep.ROOMS,"Passed","Your room list loaded. An empty list can mean no rooms are assigned yet.")
            ready=true
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            val api=e as? ApiException
            if(api!=null && api.status in 100..599)set(SetupStep.SERVER,"Passed","The server answered; the request did not complete.")
            if(e is SessionEndedException || api?.status==401 && accepted) {
                // Original ordering: the ended check runs BEFORE the rooms-
                // failure branch (Nov 15 source); ended also marks ROOMS waiting.
                ended=true
                set(SetupStep.SIGN_IN,"Needs attention","This saved sign-in is no longer accepted.","Get a fresh connection link.")
                set(SetupStep.ROOMS,"Waiting")
                // fp66c/Gemini: a 401 from the enroll request itself arrives as a
                // plain SessionEndedException (not ApiException), so the status
                // branch above never ran and SERVER would sit on "Checking"
                // forever. Mark it Not confirmed only when still Checking; a
                // SERVER already Passed stays Passed. Pre-existing 1.8 gap.
                if(checks[SetupStep.SERVER]?.state=="Checking")set(SetupStep.SERVER,"Not confirmed","The phone could not confirm a response from the server.","Check your internet connection and server address.")
            } else if(stage==SetupStep.ROOMS) {
                set(SetupStep.ROOMS,"Not confirmed","Your sign-in was accepted, but rooms did not load.","Tap Retry room loading. Your link will not be sent again.")
            } else if(api?.code in listOf("enrollment_expired","enrollment_used")) {
                set(SetupStep.SIGN_IN,"Needs attention",if(api?.code=="enrollment_expired")"This link expired." else "This link was already used.","Get a fresh connection link.")
            } else {
                if(checks[SetupStep.SERVER]?.state!="Passed")set(SetupStep.SERVER,"Not confirmed","The phone could not confirm a response from the server.","Check your internet connection and server address.")
                set(SetupStep.SIGN_IN,"Not confirmed",if(accepted)"Link accepted, but sign-in verification did not finish." else "The link may have reached the server. No automatic retry was made.",if(accepted)"Tap Retry sign-in check. Your link will not be sent again." else "Get a fresh connection link.")
            }
        } finally {busy=false;changed()}
    }
}

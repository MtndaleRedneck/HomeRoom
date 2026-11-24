package app.homeroom

import android.net.Uri
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class UploadRestartTest {
    @Test fun onlyExactServerMismatchCodeForcesNewUpload() {
        assertTrue(uploadMustRestart(ApiException(409,"upload_bytes_inconsistent")))
        assertFalse(uploadMustRestart(ApiException(409,"upload_offset_conflict")))
        assertFalse(uploadMustRestart(ApiException(500,"upload_bytes_inconsistent")))
    }
    @Test fun durableRestartMarkerBlocksResumeBeforeAnyUploadRequest() {
        val app=RuntimeEnvironment.getApplication();Session(app).clear();PrivacyPrefs.set(app,"lock",false)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val host=controller.get()
        val record=JSONObject().put("local_id","restart_fixture").put("upload_id","old_upload")
            .put("upload_client","old_upload_client").put("complete_client","old_complete")
            .put("post_client","old_post").put("created","2026-11-11T18:00:00Z")
            .put("name","fixture.txt").put("mime","text/plain").put("voice",false)
            .put("message","").put("room","fixture_room").put("restart_required",true)
        host.session.saveStagedUpload("restart_fixture",record)
        val restored=Session(app).stagedUploads().single()
        assertTrue(restored.getBoolean("restart_required"))
        val room=Room.from(JSONObject("""{"room_id":"fixture_room","display_name":"Fixture","main_thread_key":"t","members":[]}"""))
        val screen=AttachmentComposer(host,room,Uri.EMPTY,"fixture.txt",false,restored)
        screen.onShow()
        val status=screen.javaClass.getDeclaredField("status").apply {isAccessible=true}.get(screen) as android.widget.TextView
        val send=screen.javaClass.getDeclaredField("send").apply {isAccessible=true}.get(screen) as android.widget.Button
        assertTrue(status.text.contains("cannot resume"));assertFalse(send.isEnabled)
        assertNull(screen.javaClass.getDeclaredField("job").apply {isAccessible=true}.get(screen))
        assertEquals("old_upload",screen.javaClass.getDeclaredField("upload").apply {isAccessible=true}.get(screen))
        controller.pause().stop().destroy()
    }
}

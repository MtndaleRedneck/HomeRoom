package app.homeroom

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPayloadTest {
    private fun param(url:String,key:String):String =
        url.substringAfter("?").split("&").first {it.startsWith("$key=")}.substringAfter("=").let {URLDecoder.decode(it,"UTF-8")}

    @Test fun formCarriesFullMaxLengthUnicodeMessage() {
        val msg=("Zoë 😀 line\nñ & = ? # % ").repeat(60).take(FeedbackScreen.MAX_MESSAGE)
        assertEquals(FeedbackScreen.MAX_MESSAGE,msg.length)
        val url=FeedbackScreen.formUrl(msg,"Google Pixel 8","14","1.8")
        assertEquals(msg,param(url,FeedbackScreen.ENTRY_MESSAGE))
        assertEquals("1.8",param(url,FeedbackScreen.ENTRY_APP_VERSION))
        assertTrue(url.length<8000)
    }

    @Test fun githubBodyStaysUnderCutoffAtMaxMessage() {
        val msg="é".repeat(FeedbackScreen.MAX_MESSAGE)
        val body=msg+"\nApp version 1.8 · Android 14 · Google Pixel 8\nNothing else: no name, server or account."
        assertTrue(body.length<1500)
    }
}

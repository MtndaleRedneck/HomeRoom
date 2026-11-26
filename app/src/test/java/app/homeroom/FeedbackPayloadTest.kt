package app.homeroom

import android.text.SpannableStringBuilder
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production preview, GitHub body, form URL and length filter of FeedbackScreen. No network, no submit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FeedbackPayloadTest {
    private val v="1.8"; private val a="14"; private val m="Google Pixel 8"
    private fun param(url:String,key:String):String =
        url.substringAfter("?").split("&").first {it.startsWith("$key=")}.substringAfter("=").let {URLDecoder.decode(it,"UTF-8")}

    @Test fun previewShowsMessageAndOnlyTheDisclosedMetadata() {
        val p=FeedbackScreen.previewBlock("  it crashes\non open ",v,a,m)
        assertEquals("What will be sent:\nit crashes\non open\nApp version 1.8 · Android 14 · Google Pixel 8\nNothing else: no name, server or account.",p)
        assertTrue(FeedbackScreen.previewBlock("   ",v,a,m).contains("(your message)"))
    }

    @Test fun githubBodyIsExactlyThePreviewWithoutItsHeader() {
        val msg="Zoë 😀 line\nñ & = ? # % ".repeat(40).take(FeedbackScreen.MAX_MESSAGE)
        val url=FeedbackScreen.githubUrl(msg,v,a,m)
        val body=param(url,"body")
        assertEquals(FeedbackScreen.previewBlock(msg,v,a,m).removePrefix(FeedbackScreen.PREVIEW_HEADER),body)
        assertEquals("Feedback",param(url,"title"))
        assertTrue(body.contains(msg.trim()))
    }

    @Test fun formCarriesTheMessageAndSeparateMetadataWithoutReplyEmail() {
        val msg="Zoë 😀 line\nñ & = ? # % ".repeat(40).take(FeedbackScreen.MAX_MESSAGE)
        val url=FeedbackScreen.formUrl(msg.trim(),m,a,v)
        assertEquals(msg.trim(),param(url,FeedbackScreen.ENTRY_MESSAGE))
        assertEquals(m,param(url,FeedbackScreen.ENTRY_MODEL))
        assertEquals(a,param(url,FeedbackScreen.ENTRY_ANDROID))
        assertEquals(v,param(url,FeedbackScreen.ENTRY_APP_VERSION))
        assertFalse(url.contains("1964337132"))
        println("encoded form URL chars for a "+msg.length+"-char non-ASCII message: "+url.length)
    }

    @Test fun capDropsAnEmojiThatWouldCrossTheLimitWhole() {
        val e=SpannableStringBuilder(); e.filters=arrayOf(FeedbackScreen.messageFilter())
        e.append("x".repeat(FeedbackScreen.MAX_MESSAGE-1)); e.append("😀")
        assertEquals(FeedbackScreen.MAX_MESSAGE-1,e.length)
        assertFalse(Character.isHighSurrogate(e[e.length-1]))
    }

    @Test fun pasteOverTheCapKeepsExactlyTheCapWithoutALoneSurrogate() {
        val e=SpannableStringBuilder(); e.filters=arrayOf(FeedbackScreen.messageFilter())
        e.append("😀".repeat(700))
        assertTrue(e.length<=FeedbackScreen.MAX_MESSAGE)
        assertEquals(FeedbackScreen.MAX_MESSAGE,e.length)
        assertEquals(0,e.length%2)
        var i=0; while(i<e.length) {assertTrue(Character.isHighSurrogate(e[i]));assertTrue(Character.isLowSurrogate(e[i+1]));i+=2}
    }

    @Test fun cappedNonAsciiMessageFitsTheGitHubTextBeforeAnyShortening() {
        val msg="é".repeat(FeedbackScreen.MAX_MESSAGE)
        val body=FeedbackScreen.outgoingText(msg,v,a,m)
        assertTrue(body.startsWith(msg))
        assertEquals(msg.length+body.removePrefix(msg).length,body.length)
    }
}

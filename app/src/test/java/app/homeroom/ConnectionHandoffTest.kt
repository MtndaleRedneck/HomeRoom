package app.homeroom
import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ConnectionHandoffTest {
    private fun value(uri:String="homeroom://connect",origin:String="https://fixture.example",code:String="one-use")=Intent(Intent.ACTION_VIEW,Uri.parse(uri)).putExtra("server_origin",origin).putExtra("enrollment_code",code)
    @Test fun exactRouteAndCanonicalOrigin() {
        val h=ConnectionHandoff.from(value(origin="https://FIXTURE.example:443/"))!!
        assertEquals("https://fixture.example",h.origin);assertEquals("one-use",h.code)
    }
    @Test fun rejectsConfusedOrMalformedSources() {
        for(uri in listOf("homeroom://connect/","homeroom://connect?x=y","homeroom://connect#token=x","homeroom://evil@connect","homeroom://connect:80","https://connect"))assertNull(ConnectionHandoff.from(value(uri)))
        for(origin in listOf("http://fixture.example","https://evil@fixture.example","https://fixture.example/path","https://fixture.example?x=y","https://fixture.example#x","https://fixture.example:444","https://fixture.example\n"))assertNull(ConnectionHandoff.from(value(origin=origin)))
        assertNull(ConnectionHandoff.from(value(code="")));assertNull(ConnectionHandoff.from(value(code="a".repeat(4097))))
        assertNull(ConnectionHandoff.from(value().setAction(Intent.ACTION_SEND)))
    }
    @Test fun doesNotDecodeSecretsTwice() {
        assertEquals("literal%2Ftoken",ConnectionHandoff.from(value(code="literal%2Ftoken"))!!.code)
    }
}
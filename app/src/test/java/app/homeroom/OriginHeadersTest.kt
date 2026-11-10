package app.homeroom

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginHeadersTest {

    private fun build(base: String, method: String, route: String): Request {
        val builder = Request.Builder().url(base + route)
        ApiClient.applyOriginHeaders(builder, base, method)
        return if (method == "GET") builder.get().build()
        else builder.method(method, "{}".toRequestBody("application/json".toMediaType())).build()
    }

    @Test
    fun mutationCarriesOriginAndFetchHeaders() {
        val request = build("https://server.example", "POST", "/homeroom/v1/push/register")
        assertEquals("https://server.example", request.header("Origin"))
        assertEquals("same-origin", request.header("Sec-Fetch-Site"))
        assertEquals("cors", request.header("Sec-Fetch-Mode"))
        assertEquals("empty", request.header("Sec-Fetch-Dest"))
    }

    @Test
    fun getOmitsOriginButKeepsFetchSite() {
        val request = build("https://server.example", "GET", "/homeroom/v1/owner/rooms")
        assertNull(request.header("Origin"))
        assertEquals("same-origin", request.header("Sec-Fetch-Site"))
        assertEquals("cors", request.header("Sec-Fetch-Mode"))
        assertEquals("empty", request.header("Sec-Fetch-Dest"))
    }

    @Test
    fun noHomeroomHeadersEver() {
        for (method in listOf("GET", "POST", "PUT", "DELETE")) {
            val request = build("https://server.example", method, "/homeroom/v1/owner/session")
            assertTrue(
                method + " must not carry X-Homeroom-* headers",
                request.headers.names().none { it.startsWith("X-Homeroom-", ignoreCase = true) },
            )
        }
    }

    @Test
    fun nonDefaultPortKeptInOrigin() {
        val request = build("https://server.example:8443", "POST", "/homeroom/v1/owner/enroll")
        assertEquals("https://server.example:8443", request.header("Origin"))
    }
}

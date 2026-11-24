package app.homeroom
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[34])
class LiveReleaseDiscoveryTest {
 @Test fun publishedReleaseIsDiscoverableAndVerifiable() {
  val r=JSONObject(File("/downloads/release14/live-latest.json").readText())
  assertEquals("v1.4",r.getString("tag_name"))
  assertFalse(r.getBoolean("prerelease")); assertFalse(r.getBoolean("draft"))
  assertTrue(UpdateCheck.isNewer(r.getString("tag_name"),"1.3"))
  assertFalse(UpdateCheck.isNewer(r.getString("tag_name"),"1.4"))
  val assets=r.getJSONArray("assets")
  val a=(0 until assets.length()).map {assets.getJSONObject(it)}.first {it.getString("name").endsWith(".apk",true)}
  assertEquals("HomeRoom.apk",a.getString("name"))
  assertTrue(UpdateCheck.isTrustedDownloadUrl(a.getString("browser_download_url")))
  val digest=UpdateCheck.parseDigest(a.getString("digest"))
  val actual=MessageDigest.getInstance("SHA-256").digest(File("/downloads/release14/downloaded-HomeRoom.apk").readBytes()).joinToString("") {"%02x".format(it)}
  assertTrue(UpdateCheck.digestMatches(digest,actual))
  assertEquals("fb606c6b21db93eb3e900b0c642cf9b6b593161d27ae686d1c4e9630e71bf434",actual)
  println("LIVE RELEASE: v1.4 available to 1.3, 1.4 up-to-date; allowlisted APK and sha256 pass")
 }
}
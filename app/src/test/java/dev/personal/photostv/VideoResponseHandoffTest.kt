package dev.personal.photostv
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.*
import org.junit.Assert.*

class VideoResponseHandoffTest{
 private lateinit var previous:(String)->Unit
 @Before fun quiet(){previous=AppLog.output;AppLog.output={}}
 @After fun restore(){AppLog.output=previous}
 private val url="https://example.invalid/video"
 private val upstream=OkHttpClient()
 private fun response(body:ResponseBody="data".toResponseBody())=Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
 @Test fun differentUrlsAndNonzeroRangesDoNotConsumePendingResponse(){
  val handoff=VideoResponseHandoff(upstream);val initial=response();assertTrue(handoff.offer(initial))
  assertEquals("https://other.invalid/video",handoff.newCall(Request.Builder().url("https://other.invalid/video").build()).request().url.toString())
  assertEquals("bytes=10-",handoff.newCall(Request.Builder().url(url).header("Range","bytes=10-").build()).request().header("Range"))
  handoff.newCall(Request.Builder().url(url).build()).execute().use{assertSame(initial,it)};handoff.close()
 }
 @Test fun closeAndCancellationCloseTheOwnedBody(){
  var closed=0
  fun tracked():ResponseBody{val body="data".toResponseBody();return object:ResponseBody(){override fun contentType()=body.contentType();override fun contentLength()=body.contentLength();override fun source():BufferedSource=body.source();override fun close(){closed++;body.close()}}}
  val handoff=VideoResponseHandoff(upstream);assertTrue(handoff.offer(response(tracked())));handoff.close();assertEquals(1,closed)
  val second=VideoResponseHandoff(upstream);assertTrue(second.offer(response(tracked())));second.newCall(Request.Builder().url(url).build()).cancel();assertEquals(2,closed);second.close()
 }
 @Test fun knownSizeUsesExplicitEndForSameFileMetadataSeeks(){
  val handoff=VideoResponseHandoff(upstream)
  assertTrue(handoff.offer(response(ByteArray(128).toResponseBody())))
  assertEquals("bytes=100-127",handoff.newCall(Request.Builder().url(url).header("Range","bytes=100-").build()).request().header("Range"))
  assertEquals("bytes=100-",handoff.newCall(Request.Builder().url("https://other.invalid/video").header("Range","bytes=100-").build()).request().header("Range"))
  assertEquals("bytes=100-110",handoff.newCall(Request.Builder().url(url).header("Range","bytes=100-110").build()).request().header("Range"))
  handoff.close()
 }

}

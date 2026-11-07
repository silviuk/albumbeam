package dev.personal.photostv
import org.junit.Before
import org.junit.After
import org.junit.Test
import fi.iki.elonen.NanoHTTPD
import okhttp3.Request
import org.junit.Assert.*
import java.net.InetAddress
class VideoNetworkTest{
 private lateinit var output:(String)->Unit
 @Before fun silenceAndroidLog(){output=AppLog.output;AppLog.output={}}
 @After fun restoreLog(){AppLog.output=output}
 @Test fun prefersIpv4WithoutDroppingIpv6(){
  val v6=InetAddress.getByAddress(ByteArray(16).apply{this[15]=1})
  val v4=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
  assertEquals(listOf(v4,v6),ipv4First(listOf(v6,v4)))
 }
 @Test fun preservesFamilyRelativeOrder(){
  val one=InetAddress.getByAddress(byteArrayOf(10,0,0,1));val two=InetAddress.getByAddress(byteArrayOf(10,0,0,2))
  assertEquals(listOf(two,one),ipv4First(listOf(two,one)))
 }
 @Test fun supportsIpv6OnlyNetworks(){val v6=InetAddress.getByAddress(ByteArray(16).apply{this[15]=1});assertEquals(listOf(v6),ipv4First(listOf(v6)))}
 @Test fun redirectsWithoutLeakingAuthorizationOrSignedUrls(){
  var seenAuth:String?="unset";var encoding:String?=null
  val target=object:NanoHTTPD(0){override fun serve(session:IHTTPSession):Response{seenAuth=session.headers["authorization"];encoding=session.headers["accept-encoding"];return newFixedLengthResponse("video-test-bytes")}}
  target.start()
  val secret="SIGNED_QUERY_NOT_FOR_LOGS_92742"
  val auth="AUTH_NOT_FOR_LOGS_39170"
  val redirect=object:NanoHTTPD(0){override fun serve(session:IHTTPSession):Response= newFixedLengthResponse(Response.Status.REDIRECT,"text/plain","").apply{addHeader("Location","http://127.0.0.1:${target.listeningPort}/video?signature=$secret")}}
  try{
   redirect.start()
   videoHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${redirect.listeningPort}/source").header("Authorization","Bearer $auth").build()).execute().use{assertTrue(it.isSuccessful);assertEquals("video-test-bytes",it.body!!.string())}
   assertNull(seenAuth);assertEquals("identity",encoding)
   assertFalse(AppLog.export().contains(secret));assertFalse(AppLog.export().contains(auth))
  }finally{redirect.stop();target.stop()}
 }
 @Test fun streamingOnlyRejectsIgnoredTailBeforeConsumingBody(){
  val requests=java.util.concurrent.atomic.AtomicInteger()
  val server=object:NanoHTTPD(0){override fun serve(session:IHTTPSession):Response{
   requests.incrementAndGet()
   // An advertised huge body with no bytes: reading it would hang/fail, while
   // rejecting headers must finish immediately with the explicit seek failure.
   return newFixedLengthResponse(Response.Status.OK,"video/mp4",java.io.ByteArrayInputStream(byteArrayOf()),604366239L)
  }}
  server.start()
  try{
   val client=videoHttpClient(streamingOnly=true)
   val failure=assertThrows(java.io.IOException::class.java){client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/video").header("Range","bytes=604197510-").build()).execute().close()}
   assertTrue(hasStreamingSeekFailure(failure));assertEquals(1,requests.get())
  }finally{server.stop()}
 }
 @Test fun streamingOnlyAcceptsPartialResponse(){
  val server=object:NanoHTTPD(0){override fun serve(session:IHTTPSession):Response=newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,"video/mp4","tail metadata").apply{addHeader("Content-Range","bytes 44-56/57")}}
  server.start()
  try{videoHttpClient(streamingOnly=true).newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/video").header("Range","bytes=44-").build()).execute().use{assertEquals(206,it.code);assertEquals("tail metadata",it.body!!.string())}}finally{server.stop()}
 }
}

package dev.personal.photostv

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoSourceTest {
 private lateinit var previous:(String)->Unit
 @Before fun quiet(){previous=AppLog.output;AppLog.output={}}
 @After fun restore(){AppLog.output=previous}
 private val held=HeldCertificate.Builder().commonName("video test").addSubjectAlternativeName("127.0.0.1").ecdsa256().build()
 private val tls=HandshakeCertificates.Builder().heldCertificate(held).addTrustedCertificate(held.certificate).build()
 private fun server(block:(NanoHTTPD.IHTTPSession)->NanoHTTPD.Response)=object:NanoHTTPD(0){
  init{makeSecure(tls.sslContext().serverSocketFactory,null)}
  override fun serve(s:IHTTPSession)=block(s)
 }.apply{start()}
 private fun client()=videoHttpClient().newBuilder().followRedirects(false).sslSocketFactory(tls.sslSocketFactory(),tls.trustManager).hostnameVerifier{host,_->host=="127.0.0.1"}.readTimeout(3,TimeUnit.SECONDS).build()
 @Test fun redirectsAreResolvedBeforeThePlayerContactsTheDownloadOrigin()=runBlocking{
  var targetRequests=0;var range="";var auth=""
  val target=server{targetRequests++;NanoHTTPD.newFixedLengthResponse("video")}
  val secret="signed-query-must-stay-private"
  val source=server{s->range=s.headers["range"].orEmpty();auth=s.headers["authorization"].orEmpty();NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT,"text/plain","").apply{addHeader("Location","https://127.0.0.1:${target.listeningPort}/video?signature=$secret")}}
  try{
   val location=locateVideoSource("https://127.0.0.1:${source.listeningPort}/video=dv","private-bearer",client())
   assertEquals(target.listeningPort,location.url.port);assertEquals(0,targetRequests)
   assertEquals("bytes=0-0",range);assertEquals("Bearer private-bearer",auth)
   assertTrue(location.headers.isEmpty())
   assertFalse(location.toString().contains(secret));assertFalse(AppLog.export().contains(secret));assertFalse(AppLog.export().contains("private-bearer"))
  }finally{source.stop();target.stop()}
 }
 @Test fun relativeSameOriginRedirectRetainsAuthorization()=runBlocking{
  val source=server{NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT,"text/plain","").apply{addHeader("Location","/download?private=value")}}
  try{val location=locateVideoSource("https://127.0.0.1:${source.listeningPort}/video","token",client());assertEquals("Bearer token",location.headers["Authorization"]);assertEquals("/download",location.url.encodedPath)}finally{source.stop()}
 }
 @Test fun rejectsDowngradeAndUserInfoRedirects()=runBlocking{
  for(destination in listOf("http://127.0.0.1/video","https://user:password@127.0.0.1/video")){
   val source=server{NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT,"text/plain","").apply{addHeader("Location",destination)}}
   try{try{locateVideoSource("https://127.0.0.1:${source.listeningPort}/video","token",client());fail("Unsafe redirect accepted")}catch(expected:IllegalArgumentException){}}finally{source.stop()}
  }
 }
 @Test fun directVideoResponseKeepsOriginalOriginAndClosesTheProbe()=runBlocking{
  val source=server{NanoHTTPD.newFixedLengthResponse("video")}
  try{val base="https://127.0.0.1:${source.listeningPort}/video";val location=locateVideoSource(base,"token",client());assertEquals(base,location.url.toString());assertEquals("Bearer token",location.headers["Authorization"])}finally{source.stop()}
 }
 @Test fun cancellingSourceLookupCancelsTheUnderlyingNetworkCall()=runBlocking{
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val source=server{entered.countDown();release.await(3,TimeUnit.SECONDS);NanoHTTPD.newFixedLengthResponse("video")}
  val http=client()
  try{
   val lookup=launch{locateVideoSource("https://127.0.0.1:${source.listeningPort}/video","token",http)}
   withContext(Dispatchers.IO){assertTrue(entered.await(2,TimeUnit.SECONDS))}
   lookup.cancelAndJoin()
   withTimeout(1500){while(http.dispatcher.runningCallsCount()>0)delay(10)}
  }finally{release.countDown();source.stop()}
 }
 @Test fun stalledTlsHeadersAreBypassedWithoutWaitingForReadTimeout()=runBlocking{
  val count=java.util.concurrent.atomic.AtomicInteger()
  val release=CountDownLatch(1)
  val source=server{
   if(count.incrementAndGet()==1)release.await(5,TimeUnit.SECONDS)
   NanoHTTPD.newFixedLengthResponse("video")
  }
  val primary=client();val fallback=client()
  try{
   val started=System.nanoTime()
   val result=locateVideoSource("https://127.0.0.1:${source.listeningPort}/video","token",VideoCallFactory(primary,fallback,100))
   assertEquals("127.0.0.1",result.url.host)
   assertTrue(count.get()>=2)
   assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000)
  }finally{release.countDown();source.stop()}
 }

 @Test fun wholeFileTlsServerIsReadOnceForTailMetadataAndBackwardSeek()=runBlocking{
  val count=java.util.concurrent.atomic.AtomicInteger();val data=ByteArray(128*1024){(it%251).toByte()}
  val source=server{s->count.incrementAndGet();assertEquals("bytes=0-",s.headers["range"]);NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,"video/mp4",java.io.ByteArrayInputStream(data),data.size.toLong())}
  val directory=java.nio.file.Files.createTempDirectory("video-replay-tls").toFile()
  val replay=VideoReplayBuffer(directory,client(),1024L*1024)
  try{
   assertTrue(replay.open("https://127.0.0.1:${source.listeningPort}/video",emptyMap()))
   val bytes=ByteArray(32);assertEquals(32,replay.read(data.size-32L,bytes,0,32));assertArrayEquals(data.takeLast(32).toByteArray(),bytes)
   assertTrue(replay.open("https://127.0.0.1:${source.listeningPort}/video",emptyMap()));assertEquals(32,replay.read(44,bytes,0,32));assertArrayEquals(data.copyOfRange(44,76),bytes);assertEquals(1,count.get())
  }finally{replay.close();assertTrue(directory.listFiles().isNullOrEmpty());directory.deleteRecursively();source.stop()}
 }

}

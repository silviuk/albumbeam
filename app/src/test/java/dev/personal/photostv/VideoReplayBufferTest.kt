package dev.personal.photostv

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import okio.buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VideoReplayBufferTest{
 private lateinit var previous:(String)->Unit
 private lateinit var directory:File
 @Before fun setup(){previous=AppLog.output;AppLog.output={};directory=Files.createTempDirectory("video-buffer-test").toFile()}
 @After fun cleanup(){AppLog.output=previous;directory.deleteRecursively()}
 private class Factory(private val body:()->ResponseBody,private val status:Int=200):Call.Factory{
  val requests=AtomicInteger();var range=""
  override fun newCall(request:Request):Call=object:Call{
   private var cancelled=false
   override fun request()=request
   override fun isCanceled()=cancelled
   override fun isExecuted()=true
   override fun timeout()=Timeout.NONE
   override fun cancel(){cancelled=true}
   override fun clone():Call=newCall(request)
   override fun enqueue(callback:Callback)=error("Not used")
   override fun execute():Response {requests.incrementAndGet();range=request.header("Range").orEmpty();return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body()).build()}
  }
 }
 private val url="https://example.invalid/video?signature=private"
 @Test fun ignoredRangesReuseOneResponseForTailMetadataThenMediaAndReplay(){
  val bytes=ByteArray(1024*1024){(it%251).toByte()};val factory=Factory({bytes.toResponseBody()});val replay=VideoReplayBuffer(directory,factory,2L*bytes.size)
  try{
   assertTrue(replay.open(url,emptyMap()));assertEquals(bytes.size.toLong(),replay.length)
   val result=ByteArray(64)
   assertEquals(64,replay.read(bytes.size-64L,result,0,64));assertArrayEquals(bytes.takeLast(64).toByteArray(),result)
   // MP4's next extractor open at the beginning must not make a new network request.
   assertTrue(replay.open(url,emptyMap()));assertEquals(64,replay.read(44,result,0,64));assertArrayEquals(bytes.copyOfRange(44,108),result)
   assertEquals(64,replay.read(0,result,0,64));assertArrayEquals(bytes.copyOfRange(0,64),result)
   assertEquals(1,factory.requests.get());assertEquals("bytes=0-",factory.range)
  }finally{replay.close()}
  assertTrue(directory.listFiles().isNullOrEmpty())
 }
 @Test fun realPartialResponseUsesNormalRangeStreaming(){
  val factory=Factory({ByteArray(128).toResponseBody()},206);val replay=VideoReplayBuffer(directory,factory,1024)
  try{assertFalse(replay.open(url,emptyMap()));assertEquals(1,factory.requests.get());assertTrue(directory.listFiles().isNullOrEmpty())}finally{replay.close()}
 }
 @Test fun oversizeFilesUseDirectStreamingWithoutWritingDisk(){
  val factory=Factory({ByteArray(128).toResponseBody()});val replay=VideoReplayBuffer(directory,factory,64)
  try{assertFalse(replay.open(url,emptyMap()));assertTrue(directory.listFiles().isNullOrEmpty())}finally{replay.close()}
 }
 @Test fun lackOfDiskSpaceDoesNotOpenAProbe(){
  val factory=Factory({ByteArray(128).toResponseBody()});val replay=VideoReplayBuffer(directory,factory,0)
  try{assertFalse(replay.open(url,emptyMap()));assertEquals(0,factory.requests.get())}finally{replay.close()}
 }
 @Test fun zeroLengthAndEofReadsAreCorrect(){
  val factory=Factory({ByteArray(128).toResponseBody()});val replay=VideoReplayBuffer(directory,factory,1024)
  try{assertTrue(replay.open(url,emptyMap()));val result=ByteArray(128);assertEquals(0,replay.read(0,result,0,0));assertEquals(128,replay.read(0,result,0,128));assertEquals(-1,replay.read(128,result,0,1))}finally{replay.close()}
 }
 @Test fun cancelReaderAndShutdownDoNotLeaveMediaFiles(){
  val bytes=ByteArray(1024);val factory=Factory({bytes.toResponseBody()});val replay=VideoReplayBuffer(directory,factory,2048)
  assertTrue(replay.open(url,emptyMap()))
  try{replay.read(1000,ByteArray(8),0,8){true};fail("Cancelled read accepted")}catch(expected:IOException){}
  replay.close()
  try{replay.open(url,emptyMap());fail("Closed buffer reopened")}catch(expected:IOException){}
  assertTrue(directory.listFiles().isNullOrEmpty())
 }
 @Test fun frontMetadataVideoCanReadBeforeDownloadCompletes(){
  val release=CountDownLatch(1);val blocked=CountDownLatch(1)
  val all=ByteArray(256){it.toByte()}
  val factory=Factory({object:ResponseBody(){
   private var position=0
   private val stream=object:okio.Source{
    override fun timeout()=Timeout.NONE
    override fun close(){release.countDown()}
    override fun read(sink:okio.Buffer,byteCount:Long):Long{
     if(position==128){blocked.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))}
     if(position==256)return -1
     val count=minOf(128,byteCount.toInt(),256-position)
     sink.write(all,position,count);position+=count;return count.toLong()
    }
   }.buffer()
   override fun contentType()=null
   override fun contentLength()=256L
   override fun source()=stream
  }})
  val replay=VideoReplayBuffer(directory,factory,1024)
  try{
   assertTrue(replay.open(url,emptyMap()));assertTrue(blocked.await(2,TimeUnit.SECONDS))
   val prefix=ByteArray(32);assertEquals(32,replay.read(0,prefix,0,32));assertArrayEquals(all.copyOfRange(0,32),prefix)
   release.countDown();val tail=ByteArray(32);assertEquals(32,replay.read(224,tail,0,32));assertArrayEquals(all.copyOfRange(224,256),tail)
   assertEquals(1,factory.requests.get())
  }finally{release.countDown();replay.close()}
 }

}

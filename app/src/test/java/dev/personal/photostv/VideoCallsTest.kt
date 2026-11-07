package dev.personal.photostv

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoCallsTest {
 private lateinit var previous:(String)->Unit
 @Before fun quiet(){previous=AppLog.output;AppLog.output={}}
 @After fun restore(){AppLog.output=previous}
 private class Stub(val request:Request):Call {
  @Volatile var callback:Callback?=null;var cancelled=false;var executed=false;val queued=CountDownLatch(1)
  override fun request()=request
  override fun timeout()=Timeout.NONE
  override fun execute():Response=error("Unexpected synchronous call")
  override fun enqueue(callback:Callback){executed=true;this.callback=callback;queued.countDown()}
  override fun cancel(){cancelled=true}
  override fun isCanceled()=cancelled
  override fun isExecuted()=executed
  override fun clone():Call=Stub(request)
  fun fail(){callback!!.onFailure(this,IOException("simulated timeout"))}
  fun reply(body:ResponseBody="video".toResponseBody())=callback!!.onResponse(this,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
 }
 private class Factory:Call.Factory {
  val calls=CopyOnWriteArrayList<Stub>()
  override fun newCall(request:Request):Call=Stub(request).also{calls+=it}
  fun queued():Stub=calls.single().also{assertTrue(it.queued.await(2,TimeUnit.SECONDS))}
 }
 private class Result:Callback {
  val done=CountDownLatch(1);var response:Response?=null;var failure:IOException?=null;var count=0
  override fun onResponse(call:Call,response:Response){this.response=response;count++;done.countDown()}
  override fun onFailure(call:Call,e:IOException){failure=e;count++;done.countDown()}
 }
 private val request=Request.Builder().url("https://example.invalid/video?private=secret").header("Range","bytes=100-199").build()
 @Test fun quickPrimaryDoesNotStartSecondDownload(){
  val a=Factory();val b=Factory();val call=VideoCallFactory(a,b,60_000).newCall(request);val result=Result()
  call.enqueue(result);a.calls.single().reply();assertNotNull(result.response);assertTrue(b.calls.single().cancelled);assertFalse(b.calls.single().executed);result.response!!.close()
 }
 @Test fun slowHeadersStartFallbackAndRememberWinningTransport(){
  val a=Factory();val b=Factory();val factory=VideoCallFactory(a,b,10);val call=factory.newCall(request);val result=Result()
  call.enqueue(result);b.queued().reply();assertTrue(result.done.await(1,TimeUnit.SECONDS));assertTrue(a.calls.single().cancelled)
  val next=factory.newCall(request);assertEquals(1,a.calls.size);assertEquals(2,b.calls.size);assertEquals("bytes=100-199",next.request().header("Range"));result.response!!.close()
 }
 @Test fun immediateFailureStartsFallbackWithoutWaitingAndReportsOneFinalError(){
  val a=Factory();val b=Factory();val call=VideoCallFactory(a,b,60_000).newCall(request);val result=Result()
  call.enqueue(result);a.calls.single().fail();assertTrue(b.calls.single().executed);b.calls.single().fail();assertEquals(1,result.count);assertNotNull(result.failure)
 }
 @Test fun lateLosingBodyIsClosedAndNeverDelivered(){
  val a=Factory();val b=Factory();val call=VideoCallFactory(a,b,60_000).newCall(request);val result=Result();call.enqueue(result)
  a.calls.single().fail();b.calls.single().reply()
  var closed=false;val delegate="late".toResponseBody();val body=object:ResponseBody(){override fun contentType()=delegate.contentType();override fun contentLength()=delegate.contentLength();override fun source():BufferedSource=delegate.source();override fun close(){closed=true;delegate.close()}}
  a.calls.single().reply(body);assertTrue(closed);assertEquals(1,result.count);result.response!!.close()
 }
 @Test fun cancellationDoesNotStartFallback(){
  val a=Factory();val b=Factory();val call=VideoCallFactory(a,b,60_000).newCall(request);val result=Result();call.enqueue(result);call.cancel();a.calls.single().fail()
  assertTrue(call.isCanceled());assertTrue(a.calls.single().cancelled);assertTrue(b.calls.single().cancelled);assertFalse(b.calls.single().executed);assertEquals(1,result.count)
 }
 @Test fun cloneHasIndependentExecutionAndCancellation(){
  val a=Factory();val b=Factory();val call=VideoCallFactory(a,b,60_000).newCall(request);call.enqueue(Result());val clone=call.clone();call.cancel()
  assertFalse(clone.isExecuted());assertFalse(clone.isCanceled());clone.cancel()
 }
}

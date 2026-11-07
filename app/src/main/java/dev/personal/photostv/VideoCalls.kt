package dev.personal.photostv

import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Race headers only after a slow Google open. Independent HTTP/1.1 pool avoids a stalled
 * HTTP/2 route. The first response owns the body; the losing call/body is always closed.
 * No whole-file download, codec conversion, or bearer propagation is introduced. */
class VideoCallFactory(
 private val primary:Call.Factory,
 private val compatibility:Call.Factory,
 private val hedgeDelayMs:Long=2500,
 private val preferCompatibility:AtomicBoolean=AtomicBoolean()
):Call.Factory {
 override fun newCall(request:Request):Call=if(preferCompatibility.get())compatibility.newCall(request)else HeaderRace(request)
 private inner class HeaderRace(private val original:Request):Call {
  private val first=primary.newCall(original)
  private val second=compatibility.newCall(original)
  private val executed=AtomicBoolean()
  private val completed=AtomicBoolean()
  private val cancelled=AtomicBoolean()
  private val fallbackStarted=AtomicBoolean()
  private val failures=java.util.concurrent.atomic.AtomicInteger()
  @Volatile private var scheduled:ScheduledFuture<*>?=null
  override fun request()=original
  override fun timeout():Timeout=first.timeout()
  override fun isExecuted()=executed.get()
  override fun isCanceled()=cancelled.get()
  override fun clone():Call=HeaderRace(original)
  override fun cancel(){cancelled.set(true);scheduled?.cancel(false);first.cancel();second.cancel()}
  override fun execute():Response {
   val latch=CountDownLatch(1);var response:Response?=null;var failure:IOException?=null
   enqueue(object:Callback{
    override fun onResponse(call:Call,value:Response){response=value;latch.countDown()}
    override fun onFailure(call:Call,e:IOException){failure=e;latch.countDown()}
   })
   try{latch.await()}catch(e:InterruptedException){cancel();Thread.currentThread().interrupt();throw IOException("Video open interrupted",e)}
   failure?.let{throw it};return response!!
  }
  override fun enqueue(callback:Callback){
   check(executed.compareAndSet(false,true)){"Already executed"}
   fun rejected(e:IOException){if(completed.compareAndSet(false,true)){scheduled?.cancel(false);callback.onFailure(this,e)}}
   lateinit var startFallback:()->Unit
   fun listener(alternate:Boolean)=object:Callback{
    override fun onResponse(call:Call,response:Response){
     if(cancelled.get()){response.close();rejected(IOException("Canceled"));return}
     if(completed.compareAndSet(false,true)){
      scheduled?.cancel(false)
      if(alternate){preferCompatibility.set(true);first.cancel()}else second.cancel()
      AppLog.add("Video: response headers won by ${if(alternate)"HTTP/1.1 fallback" else "primary connection"}; losing request cancelled")
      callback.onResponse(this@HeaderRace,response)
     }else response.close()
    }
    override fun onFailure(call:Call,e:IOException){
     if(completed.get())return
     if(hasStreamingSeekFailure(e)){rejected(e);first.cancel();second.cancel();return}
     if(cancelled.get()){rejected(IOException("Canceled"));return}
     val count=failures.incrementAndGet()
     if(!alternate)startFallback()
     if(count==2)rejected(e)
    }
   }
   startFallback={
    if(!completed.get() && !cancelled.get() && fallbackStarted.compareAndSet(false,true)){
     AppLog.add("Video: slow/failed response headers; opening independent HTTP/1.1 fallback")
     second.enqueue(listener(true))
    }
   }
   scheduled=timer.schedule({startFallback()},hedgeDelayMs,TimeUnit.MILLISECONDS)
   if(cancelled.get())rejected(IOException("Canceled"))else first.enqueue(listener(false))
  }
 }
 companion object {
  private val timer=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"video-header-fallback").apply{isDaemon=true}}
 }
}

package dev.personal.photostv

import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse the already opened initial response when disk buffering is unsuitable. */
class VideoResponseHandoff(private val upstream:Call.Factory):Call.Factory{
 private var pending:Response?=null
 private var closed=false
 private var knownUrl:HttpUrl?=null
 private var knownLength=-1L
 @Synchronized fun offer(response:Response):Boolean {
  if(closed || pending!=null)return false
  knownUrl=response.request.url;knownLength=response.body?.contentLength() ?: -1
  pending=response;return true
 }
 @Synchronized override fun newCall(request:Request):Call {
  val response=pending
  val range=request.header("Range")
  if(response==null || response.request.url!=request.url || request.method!="GET" || (range!=null && !range.startsWith("bytes=0-"))){
   // Explicit start/end byte ranges also work on servers that reject open-ended seeks.
   // Use only the size of this exact response URL; never reuse it across origins/files.
   val start=range?.takeIf{it.startsWith("bytes=") && it.endsWith("-")}?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
   val bounded=if(request.method=="GET" && request.url==knownUrl && start!=null && start>=0 && start<knownLength)
    request.newBuilder().header("Range","bytes=$start-${knownLength-1}").build() else request
   return upstream.newCall(bounded)
  }
  pending=null
  AppLog.add("Video: passing initial response to player without another Google request")
  return object:Call{
   private val executed=AtomicBoolean();private val cancelled=AtomicBoolean()
   override fun request()=request
   override fun isExecuted()=executed.get()
   override fun isCanceled()=cancelled.get()
   override fun timeout()=Timeout.NONE
   override fun clone():Call=upstream.newCall(request)
   override fun cancel(){cancelled.set(true);response.close()}
   override fun execute():Response{
    check(executed.compareAndSet(false,true)){"Already executed"}
    if(cancelled.get()){response.close();throw IOException("Canceled")}
    return response
   }
   override fun enqueue(callback:Callback){val value=try{execute()}catch(e:IOException){callback.onFailure(this,e);return};callback.onResponse(this,value)}
  }
 }
 @Synchronized fun close(){closed=true;pending?.close();pending=null;knownUrl=null;knownLength=-1}
}

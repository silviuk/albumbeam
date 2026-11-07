package dev.personal.photostv

import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse the already opened initial response when disk buffering is unsuitable. */
class VideoResponseHandoff(private val upstream:Call.Factory):Call.Factory{
 private var pending:Response?=null
 private var closed=false
 @Synchronized fun offer(response:Response):Boolean {
  if(closed || pending!=null)return false
  pending=response;return true
 }
 @Synchronized override fun newCall(request:Request):Call {
  val response=pending
  val range=request.header("Range")
  if(response==null || response.request.url!=request.url || request.method!="GET" || (range!=null && !range.startsWith("bytes=0-")))return upstream.newCall(request)
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
 @Synchronized fun close(){closed=true;pending?.close();pending=null}
}

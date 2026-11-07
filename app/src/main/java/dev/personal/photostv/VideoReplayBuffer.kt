package dev.personal.photostv

import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/** One sequential response for servers that ignore Range. The extractor can seek back to
 * already downloaded bytes without reopening Google. Only media bytes touch private disk.
 * Servers that return 206 retain the normal range-capable Media3 path. */
class VideoReplayBuffer(private val directory:File,private val calls:Call.Factory,private val maxBytes:Long){
 private val lock=java.lang.Object()
 private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"video-replay-buffer").apply{isDaemon=true}}
 private var started=false
 private var closed=false
 private var mode=0 // pending, buffered, direct
 private var downloaded=0L
 private var wanted=0L
 private var finished=false
 private var failure:IOException?=null
 private var file:File?=null
 private var disk:RandomAccessFile?=null
 private var call:Call?=null
 private var expected=-1L
 val length:Long get()=synchronized(lock){expected}
 fun open(url:String,headers:Map<String,String>,cancelled:()->Boolean={false}):Boolean=synchronized(lock){
  if(!started){
   started=true
   if(maxBytes<=0)mode=2 else worker.execute{download(url,headers)}
  }
  while(mode==0){checkOpen(cancelled);waitForProgress()}
  checkOpen(cancelled)
  mode==1
 }
 private fun waitForProgress(){try{lock.wait(100)}catch(e:InterruptedException){Thread.currentThread().interrupt();throw java.io.InterruptedIOException("Video buffer interrupted")}}
 private fun checkOpen(cancelled:()->Boolean){if(closed || cancelled())throw IOException("Video buffer closed")}
 fun read(position:Long,target:ByteArray,offset:Int,count:Int,cancelled:()->Boolean={false}):Int=synchronized(lock){
  require(position>=0 && offset>=0 && count>=0 && offset<=target.size-count && position<=Long.MAX_VALUE-count)
  if(count==0)return 0
  checkOpen(cancelled)
  wanted=maxOf(wanted,position+count);lock.notifyAll()
  while(position>=downloaded && !finished && failure==null){checkOpen(cancelled);waitForProgress()}
  checkOpen(cancelled)
  if(position<downloaded){
   val bytes=minOf(count.toLong(),downloaded-position).toInt()
   disk!!.seek(position);disk!!.readFully(target,offset,bytes);return bytes
  }
  failure?.let{throw it}
  -1
 }
 private fun download(url:String,headers:Map<String,String>){
  val startedAt=System.nanoTime()
  try{
   val request=Request.Builder().url(url).apply{headers.forEach{(key,value)->header(key,value)}}
    .header("Range","bytes=0-").header("Accept-Encoding","identity").build()
   val opening=calls.newCall(request)
   synchronized(lock){if(closed)return;call=opening}
   opening.execute().use{response->
    if(!response.isSuccessful)throw IOException("Video download HTTP ${response.code}")
    val body=response.body ?: throw IOException("Empty video response")
    synchronized(lock){
     if(closed)return
     // No guessing from Accept-Ranges: the actual 206 response decides.
     if(response.code==206 || body.contentLength()>maxBytes){
      mode=2;lock.notifyAll()
      AppLog.add(if(response.code==206)"Video buffer: server supports ranges; using direct range streaming" else "Video buffer: file exceeds disk budget; using direct streaming")
      return
     }
     if(response.code!=200)throw IOException("Unexpected video response")
     directory.mkdirs();file=File.createTempFile("replay-",".tmp",directory);disk=RandomAccessFile(file!!,"rw")
     expected=body.contentLength();mode=1;lock.notifyAll()
     AppLog.add("Video buffer: server returned whole file; reuse one response for MP4 metadata, seeking and replay; bytes=$expected; disk budget=$maxBytes")
    }
    val bytes=ByteArray(64*1024)
    body.byteStream().use{input->
     while(true){
      synchronized(lock){while(!closed && downloaded>wanted+8L*1024*1024)waitForProgress();if(closed)return}
      val count=input.read(bytes)
      if(count<0)break
      synchronized(lock){
       if(closed)return
       if(downloaded+count>maxBytes)throw IOException("Video disk budget reached")
       if(downloaded==0L)AppLog.add("Video buffer: first network bytes after ${(System.nanoTime()-startedAt)/1_000_000}ms")
       disk!!.seek(downloaded);disk!!.write(bytes,0,count);downloaded+=count;lock.notifyAll()
      }
     }
    }
    synchronized(lock){
     if(expected>=0 && downloaded!=expected)throw IOException("Incomplete video body")
     expected=downloaded;finished=true;lock.notifyAll()
     AppLog.add("Video buffer: download complete; $downloaded bytes reusable until leaving this video")
    }
   }
  }catch(error:Exception){
   synchronized(lock){if(!closed){failure=if(error is IOException)error else IOException("Video buffer failed",error);mode=2;lock.notifyAll();AppLog.add("Video buffer: ${error.javaClass.simpleName}; next open uses direct streaming")}}
  }
 }
 fun wakeReaders()=synchronized(lock){lock.notifyAll()}
 fun close(){
  synchronized(lock){if(closed)return;closed=true;call?.cancel();runCatching{disk?.close()};file?.delete();lock.notifyAll()}
  worker.shutdownNow()
 }
}

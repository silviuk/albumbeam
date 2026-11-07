@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.personal.photostv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import java.io.File
import java.io.IOException

/** The buffer belongs to a single viewer, not a single extractor open/close cycle. */
class ReplayVideoDataSource(private val replay:VideoReplayBuffer,private val upstream:DataSource.Factory,private val headers:()->Map<String,String>):BaseDataSource(false){
 @Volatile private var stopped=true
 private var uri:Uri?=null
 private var position=0L
 private var remaining=C.LENGTH_UNSET.toLong()
 private var direct:DataSource?=null
 private var active=false
 override fun open(spec:DataSpec):Long{
  stopped=false;uri=spec.uri;position=spec.position;transferInitializing(spec)
  if(!replay.open(spec.uri.toString(),headers()+spec.httpRequestHeaders){stopped}){
   replay.directOpen(spec.position)
   return upstream.createDataSource().also{source->
    direct=source
    source.addTransferListener(object:TransferListener{
     override fun onTransferInitializing(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
     override fun onTransferStart(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
     override fun onBytesTransferred(source:DataSource,spec:DataSpec,isNetwork:Boolean,bytesTransferred:Int){replay.directRead(bytesTransferred)}
     override fun onTransferEnd(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
    })
   }.open(spec)
  }
  val length=replay.length
  if(length>=0 && position>length)throw IOException("Video position past end")
  remaining=if(spec.length!=C.LENGTH_UNSET.toLong())spec.length else if(length>=0)length-position else C.LENGTH_UNSET.toLong()
  active=true;transferStarted(spec)
  AppLog.add("Video buffer: extractor open at $position; reusing the current download and buffered bytes")
  return remaining
 }
 override fun read(buffer:ByteArray,offset:Int,length:Int):Int{
  direct?.let{return it.read(buffer,offset,length)}
  if(length==0)return 0
  if(remaining==0L)return C.RESULT_END_OF_INPUT
  val count=replay.read(position,buffer,offset,if(remaining<0)length else minOf(length.toLong(),remaining).toInt()){stopped}
  if(count>0){position+=count;if(remaining>=0)remaining-=count;bytesTransferred(count)}
  return count
 }
 override fun getUri()=direct?.uri ?: uri
 override fun getResponseHeaders():Map<String,List<String>> = direct?.responseHeaders ?: emptyMap()
 override fun close(){stopped=true;replay.wakeReaders();direct?.close();direct=null;uri=null;if(active){active=false;transferEnded()}}
}
fun videoReplayBudget(directory:File)=minOf(1024L*1024*1024,(directory.usableSpace-128L*1024*1024).coerceAtLeast(0))

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.personal.photostv

import androidx.media3.datasource.*

/** Per-open timing distinguishes a connection stall from an expensive MP4 seek/download.
 * No media URL, grant or signed query is retained or logged. */
class VideoTransferLog:TransferListener {
 private var started=0L
 private var total=0L
 private var first=true
 override fun onTransferInitializing(source:DataSource,spec:DataSpec,isNetwork:Boolean){
  started=System.nanoTime();total=0;first=true
  AppLog.add("Video load: offset=${spec.position}; requested bytes=${spec.length}")
 }
 override fun onTransferStart(source:DataSource,spec:DataSpec,isNetwork:Boolean){AppLog.add("Video load: body opened after ${elapsed()}ms; offset=${spec.position}")}
 override fun onBytesTransferred(source:DataSource,spec:DataSpec,isNetwork:Boolean,bytesTransferred:Int){
  total+=bytesTransferred
  if(first){first=false;AppLog.add("Video load: first bytes after ${elapsed()}ms; offset=${spec.position}")}
 }
 override fun onTransferEnd(source:DataSource,spec:DataSpec,isNetwork:Boolean){
  val ms=elapsed().coerceAtLeast(1)
  AppLog.add("Video load: closed; offset=${spec.position}; bytes=$total; elapsed=${ms}ms; mean=${"%.2f".format(total*8.0/ms/1000)} Mbps")
 }
 private fun elapsed()=(System.nanoTime()-started)/1_000_000
}

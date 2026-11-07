@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.personal.photostv

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

val videoMimePreference=listOf("video/av01","video/hevc","video/dolby-vision","video/x-vnd.on2.vp9","video/avc")
fun codecPreference(mime:String)=when(mime){"video/av01"->4;"video/hevc","video/dolby-vision"->3;"video/x-vnd.on2.vp9"->2;"video/avc"->1;else->0}
fun hardwareFirstDecoderSelector()=MediaCodecSelector{mime,secure,tunneling->
 MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling).sortedByDescending{it.hardwareAccelerated}
}
fun hardwareVideoMimes()=videoMimePreference.filter{mime->runCatching{hardwareFirstDecoderSelector().getDecoderInfos(mime,false,false).any{it.hardwareAccelerated}}.getOrDefault(false)}
fun hasHardwareDecoder(context:Context,format:Format)=runCatching{
 hardwareFirstDecoderSelector().getDecoderInfos(format.sampleMimeType ?: "",false,false).any{it.hardwareAccelerated && it.isFormatSupported(context,format)}
}.getOrDefault(false)
fun decoderCapabilities():String=videoMimePreference.joinToString("\n"){mime->
 val decoders=runCatching{hardwareFirstDecoderSelector().getDecoderInfos(mime,false,false)}.getOrDefault(emptyList())
 "TV codec $mime: "+decoders.joinToString("; "){decoder->
  val uhd=runCatching{decoder.isVideoSizeAndRateSupported(3840,2160,60.0)}.getOrDefault(false)
  "${decoder.name} (${if(decoder.hardwareAccelerated)"hardware" else "software"}; 4K60 size/rate=$uhd)"
 }.ifBlank{"unavailable"}
}
/** Pick a format supported by hardware first, then resolution/fps, then codec efficiency.
 * This selects supplied tracks only; it does not request or generate a new encoding. */
data class VideoTrackPreference(val hardware:Boolean,val quality:PlaybackQuality,val mime:String):Comparable<VideoTrackPreference>{
 override fun compareTo(other:VideoTrackPreference):Int {
  compareValues(hardware,other.hardware).takeIf{it!=0}?.let{return it}
  val pixels=quality.width.coerceAtLeast(0).toLong()*quality.height.coerceAtLeast(0)
  val otherPixels=other.quality.width.coerceAtLeast(0).toLong()*other.quality.height.coerceAtLeast(0)
  compareValues(pixels,otherPixels).takeIf{it!=0}?.let{return it}
  compareValues(quality.frameRate.takeIf{it.isFinite() && it>0} ?: 0f,other.quality.frameRate.takeIf{it.isFinite() && it>0} ?: 0f).takeIf{it!=0}?.let{return it}
  compareValues(codecPreference(mime),codecPreference(other.mime)).takeIf{it!=0}?.let{return it}
  return compareValues(quality.bitrate.coerceAtLeast(0),other.quality.bitrate.coerceAtLeast(0))
 }
}

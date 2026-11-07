package dev.personal.photostv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.request.*
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fit within a bounded 4K decode budget; preserve aspect ratio and avoid thumbnail reuse. */
data class PhotoPixels(val width:Int,val height:Int)
fun photoPixels(width:Int,height:Int):PhotoPixels{
 val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
 val factor=minOf(1.0,3840.0/maxOf(w,h),2160.0/minOf(w,h))
 return PhotoPixels(maxOf(1,(w*factor).toInt()),maxOf(1,(h*factor).toInt()))
}
enum class PhotoQuality(val label:String){SCREEN("Screen quality • up to 4K"),FULL_HD("Full HD • up to 1080p"),SOURCE_FILE("Full-size photo file • render up to 4K")}
fun photoRequest(base:String,pixels:PhotoPixels,quality:PhotoQuality)=if(quality==PhotoQuality.SOURCE_FILE) "$base=d" else "$base=w${pixels.width}-h${pixels.height}"
fun qualityPixels(pixels:PhotoPixels,quality:PhotoQuality):PhotoPixels{
 if(quality!=PhotoQuality.FULL_HD)return pixels
 val scale=minOf(1.0,1920.0/maxOf(pixels.width,pixels.height),1080.0/minOf(pixels.width,pixels.height))
 return PhotoPixels(maxOf(1,(pixels.width*scale).toInt()),maxOf(1,(pixels.height*scale).toInt()))
}
fun sourceDimensions(file:JSONObject):String{
 val metadata=file.optJSONObject("mediaFileMetadata")
 val w=metadata?.optInt("width",0) ?: 0;val h=metadata?.optInt("height",0) ?: 0
 return if(w>0 && h>0) "${w}x${h}" else "unknown"
}
data class VideoQualityOption(val label:String,val selected:Boolean,val width:Int,val height:Int,val mime:String,val codec:String,val choose:()->Unit)
fun videoCodecLabel(mime:String)=when(mime){"video/hevc"->"HEVC / H.265";"video/avc"->"AVC / H.264";"video/av01"->"AV1";"video/x-vnd.on2.vp9"->"VP9";"video/dolby-vision"->"Dolby Vision";else->mime.ifBlank{"Unknown codec"}}
fun videoReadiness(file:JSONObject)=file.optJSONObject("mediaFileMetadata")?.optJSONObject("videoMetadata")?.optString("processingStatus").orEmpty()

/** Logs error types/codes only: exception messages may contain protected media URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun mediaError(error:Throwable):String=generateSequence(error){it.cause}.take(8).joinToString(" → "){
 when(it){
  is HttpDataSource.InvalidResponseCodeException -> "HTTP ${it.responseCode}"
  is PlaybackException -> "${it.javaClass.simpleName}(${it.errorCodeName})"
  is coil.network.HttpException -> "Photo HTTP ${it.response.code}"
  else -> it.javaClass.simpleName
 }
}

private class PhotoSurface(context:Context,private val pixels:PhotoPixels):SurfaceView(context),SurfaceHolder.Callback{
 var bitmap:Bitmap?=null
 private var viewport=MediaViewport()
 private var warmth=0f
 private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
 private val destination=RectF()
 fun updateView(next:MediaViewport,tint:Float){
  if(viewport==next && warmth==tint)return
  viewport=next
  if(warmth!=tint){
   warmth=tint
   val c=warmthChannels(tint)
   paint.colorFilter=if(tint<=0f)null else ColorMatrixColorFilter(ColorMatrix(floatArrayOf(c[0],0f,0f,0f,0f, 0f,c[1],0f,0f,0f, 0f,0f,c[2],0f,0f, 0f,0f,0f,1f,0f)))
  }
  drawPhoto()
 }
 init{holder.addCallback(this);holder.setFixedSize(pixels.width,pixels.height)}
 override fun surfaceCreated(holder:SurfaceHolder){drawPhoto()}
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){AppLog.add("Photo surface buffer ${width}x${height}; view ${this.width}x${this.height}");drawPhoto()}
 override fun surfaceDestroyed(holder:SurfaceHolder){}
 fun drawPhoto(){
  if(!holder.surface.isValid)return
  try{
   val canvas=holder.lockHardwareCanvas() ?: return
   try{
    canvas.drawColor(android.graphics.Color.BLACK)
    bitmap?.let{image->
     val bounds=photoBounds(canvas.width.toFloat(),canvas.height.toFloat(),image.width.toFloat(),image.height.toFloat(),viewport)
     destination.set(bounds.left,bounds.top,bounds.left+bounds.width,bounds.top+bounds.height)
     canvas.drawBitmap(image,null,destination,paint)
    }
   }finally{holder.unlockCanvasAndPost(canvas)}
  }catch(error:Exception){AppLog.add("Photo surface draw failed: ${error.javaClass.simpleName}")}
 }
}

@Composable fun PhotoViewer(file:JSONObject,token:String,loader:ImageLoader,modifier:Modifier,quality:PhotoQuality,viewport:MediaViewport=MediaViewport(),warmth:Float=0f,onAspect:(Float)->Unit={},onInfo:(String)->Unit,onLoaded:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val display=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
 val mode=display.supportedModes.maxByOrNull{it.physicalWidth.toLong()*it.physicalHeight} ?: display.mode
 val current=display.mode
 val multiplier=maxOf(1.0,mode.physicalWidth.toDouble()/current.physicalWidth,mode.physicalHeight.toDouble()/current.physicalHeight)
 // Keep the current display aspect ratio even if a wider cinema mode is supported.
 val pixels=qualityPixels(photoPixels((current.physicalWidth*multiplier).toInt(),(current.physicalHeight*multiplier).toInt()),quality)
 val surface=remember(quality){PhotoSurface(context,pixels)}
 val aspectCallback by rememberUpdatedState(onAspect)
 val infoCallback by rememberUpdatedState(onInfo)
 var failure by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(true)}
 val loadedCallback by rememberUpdatedState(onLoaded)
 val failedCallback by rememberUpdatedState(onFailure)
 LaunchedEffect(file.optString("baseUrl"),token,quality){
  loading=true;surface.bitmap=null;surface.drawPhoto()
  val meta=file.optJSONObject("mediaFileMetadata")
  AppLog.add("Photo request ${pixels.width}x${pixels.height}; source ${meta?.optInt("width")}x${meta?.optInt("height")}; display mode ${display.mode.physicalWidth}x${display.mode.physicalHeight}")
  val request=ImageRequest.Builder(context)
   .data(if(nativeFile(file))mediaUrl(file,"") else photoRequest(file.getString("baseUrl"),pixels,quality))
   .addHeader("Authorization","Bearer $token")
   .size(pixels.width,pixels.height).scale(Scale.FIT).precision(Precision.EXACT)
   .allowHardware(false).bitmapConfig(Bitmap.Config.ARGB_8888)
   .diskCachePolicy(CachePolicy.DISABLED)
   .memoryCacheKey("full:${file.getString("baseUrl")}:${pixels.width}x${pixels.height}:$quality:${token.hashCode()}").build()
  val activeLoader=if(nativeFile(file))ImageLoader.Builder(context).okHttpClient(mediaClient(file)).diskCachePolicy(CachePolicy.DISABLED).build() else loader
  val result=try{activeLoader.execute(request)}finally{if(activeLoader!==loader)activeLoader.shutdown()}
  loading=false
  if(result is SuccessResult){
   val image=(result.drawable as? BitmapDrawable)?.bitmap
   if(image!=null){aspectCallback(image.width.toFloat()/image.height);surface.bitmap=image;surface.drawPhoto();AppLog.add("Photo decoded ${image.width}x${image.height}; ${image.byteCount/1024} KiB; ${result.dataSource}");infoCallback("${quality.label}\nDecoded photo: ${image.width}x${image.height}\nSurface target: ${pixels.width}x${pixels.height}\nDisplay output: ${current.physicalWidth}x${current.physicalHeight}\nTransport: ${mediaTransport(file)}\nFull-size files are decoded to a bounded TV resolution; no cropping.");loadedCallback()}
   else{failure="Photo format could not be rendered.";failedCallback(failure)}
  }else if(result is ErrorResult){failure="Photo failed: ${mediaError(result.throwable)}. Retry to refresh access.";AppLog.add(failure);failedCallback(failure)}
 }
 DisposableEffect(surface){onDispose{surface.bitmap=null}}
 Box(modifier.background(Color.Black)){
  AndroidView(factory={surface},update={it.updateView(viewport,warmth)},modifier=Modifier.fillMaxSize())
  if(loading)Text("Loading full-resolution photo…",modifier=Modifier.padding(top=100.dp,start=24.dp))
  if(failure.isNotBlank())Text(failure,modifier=Modifier.padding(top=100.dp,start=24.dp))
 }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ZoomVideoFrame(context:Context,player:Player):FrameLayout(context){
 private var viewport=MediaViewport()
 private val video=PlayerView(context).apply{this.player=player;useController=false;setEnableComposeSurfaceSyncWorkaround(true)}
 init{clipChildren=true;clipToPadding=true;addView(video,LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))}
 fun updateViewport(next:MediaViewport){if(viewport==next)return;viewport=next;transform()}
 private fun transform(){video.scaleX=viewport.scale;video.scaleY=viewport.scale;video.translationX=viewport.x*width;video.translationY=viewport.y*height}
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);transform()}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun VideoViewer(file:JSONObject,token:String,modifier:Modifier,fastStartup:Boolean,http1Only:Boolean=false,allowFullFile:Boolean=false,viewport:MediaViewport=MediaViewport(),zoomMode:Boolean=false,onAspect:(Float)->Unit={},controlsVisible:Boolean,onActivity:(Boolean)->Unit,onControlReady:(((String)->Unit)?)->Unit,onState:(String)->Unit,onInfo:(String)->Unit,onOptions:(List<VideoQualityOption>)->Unit,onEnded:()->Unit,onInteraction:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val aspectCallback by rememberUpdatedState(onAspect)
 val chromeNow by rememberUpdatedState(controlsVisible);val activityCallback by rememberUpdatedState(onActivity);val controlCallback by rememberUpdatedState(onControlReady);val stateCallback by rememberUpdatedState(onState)
 val infoCallback by rememberUpdatedState(onInfo);val optionsCallback by rememberUpdatedState(onOptions)
 val playbackScope=rememberCoroutineScope()
 var position by remember{mutableLongStateOf(0L)};var duration by remember{mutableLongStateOf(0L)}
 var decoderInfo by remember{mutableStateOf("Decoder: waiting for playback")}
 var decoded by remember{mutableStateOf("Decoded resolution: waiting for playback")};var trackInfo by remember{mutableStateOf("")}
 val ended by rememberUpdatedState(onEnded);val interacted by rememberUpdatedState(onInteraction);val failed by rememberUpdatedState(onFailure)
 val outputMode=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.mode
 val outputInfo="Display output: ${outputMode.physicalWidth}x${outputMode.physicalHeight} @ ${outputMode.refreshRate} Hz\nTransport: ${mediaTransport(file)}"
 val processing=videoReadiness(file)
 var message by remember{mutableStateOf("Preparing video…")}
 var failureMessage by remember{mutableStateOf("")}
 var preparation by remember{mutableStateOf("")}

 if(processing=="PROCESSING" || processing=="FAILED"){
  val reason=if(processing=="PROCESSING") "Google Photos is still processing this video. Retry later." else "Google Photos could not process this video."
  LaunchedEffect(reason){AppLog.add("Video status: $processing");failed(reason)}
  Box(modifier.background(Color.Black)){Text(reason,Modifier.padding(top=100.dp,start=24.dp))}
  return
 }
 val hardwareMimes=remember{hardwareVideoMimes()}
 val http=remember{mediaClient(file,http1Only,streamingOnly=!allowFullFile)}
 val compatibilityPreferred=remember{java.util.concurrent.atomic.AtomicBoolean()}
 val rawCalls=remember{if(directFile(file) || http1Only)http else VideoCallFactory(http,videoHttpClient(true,streamingOnly=!allowFullFile),preferCompatibility=compatibilityPreferred)}
 val handoff=remember{if(directFile(file))null else VideoResponseHandoff(rawCalls)}
 val calls=handoff ?: rawCalls
 val sourceFactory=remember{OkHttpDataSource.Factory(calls).setUserAgent("PhotosBeam/${BuildConfig.VERSION_NAME}").setTransferListener(VideoTransferLog())}
 val replay=remember{if(directFile(file) || !allowFullFile)null else VideoReplayBuffer(java.io.File(context.cacheDir,"video-replay"),calls,videoReplayBudget(context.cacheDir)){handoff?.offer(it) ?: false}}
 val replayHeaders=remember{mutableStateOf<Map<String,String>>(emptyMap())}
 val playbackFactory=remember{if(replay==null)sourceFactory else androidx.media3.datasource.DataSource.Factory{ReplayVideoDataSource(replay,sourceFactory){replayHeaders.value}.also{it.addTransferListener(VideoTransferLog())}}}
 var automaticTrack by remember{mutableStateOf(true)}
 val bestTrack=remember{mutableStateOf<VideoQualityOption?>(null)}
 var suppliedTracks by remember{mutableStateOf<List<VideoQualityOption>>(emptyList())}
 LaunchedEffect(suppliedTracks,automaticTrack){optionsCallback(listOfNotNull(bestTrack.value?.copy(selected=automaticTrack))+suppliedTracks)}

 val resolver=remember{if(directFile(file))null else {
  val primary=videoHttpClient(http1Only).newBuilder().followRedirects(false).readTimeout(10,TimeUnit.SECONDS).build()
  if(http1Only)primary else VideoCallFactory(primary,videoHttpClient(true).newBuilder().followRedirects(false).readTimeout(10,TimeUnit.SECONDS).build(),preferCompatibility=compatibilityPreferred)
 }}
 val player=remember{
  AppLog.add("Video: preparing progressive playback; processing=${processing.ifBlank{"unspecified"}}; MIME=${file.optString("mimeType")}")
  ExoPlayer.Builder(context,DefaultRenderersFactory(context).setMediaCodecSelector(hardwareFirstDecoderSelector()).setEnableDecoderFallback(true))
   .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(3000,10000,750,1500).setTargetBufferBytes(48*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())
   .setMediaSourceFactory(DefaultMediaSourceFactory(playbackFactory,streamingExtractors(fastStartup)).setLoadErrorHandlingPolicy(object:androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1){
    override fun getRetryDelayMsFor(info:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo):Long =
     if(hasStreamingSeekFailure(info.exception))C.TIME_UNSET else super.getRetryDelayMsFor(info)
   })).build()
 }
 DisposableEffect(player){
  val startTime=android.os.SystemClock.elapsedRealtime()
  AppLog.add("Video: full-file preparation=${if(allowFullFile && !directFile(file)) "explicitly enabled" else "OFF (streaming only)"}")
  AppLog.add("Video: ${if(webFile(file)) "browser Google streaming" else if(nativeFile(file)) "phone/provider streaming" else "direct Google streaming"}; fast startup=$fastStartup; startup buffer 750ms; no local transcode")
  controlCallback{action->when(action){
   "play"->{if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()}
   "pause"->player.pause()
   "toggle"->{if(player.playWhenReady)player.pause()else{if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()}}
   "seek-back","seek-forward"->{
    interacted()
    if(player.isCurrentMediaItemSeekable){val target=seekPosition(player.currentPosition,player.duration,if(action=="seek-back")-10000L else 10000L);player.seekTo(target);AppLog.add("Video: remote seek to ${target/1000}s")}
    else AppLog.add("Video: seek unavailable until a seekable video is ready")
   }
  }}
  var choseMaximumTrack=false
  val listener=object:Player.Listener{
   override fun onPlaybackStateChanged(state:Int){
    AppLog.add("Video playback state: "+when(state){Player.STATE_BUFFERING->"BUFFERING";Player.STATE_READY->"READY";Player.STATE_ENDED->"ENDED";else->"IDLE"})
    stateCallback(when(state){Player.STATE_BUFFERING->if(player.playWhenReady)"Buffering" else "Paused";Player.STATE_READY->if(player.playWhenReady) "Playing" else "Paused";Player.STATE_ENDED->"Ended";else->"Idle"})
    message=if(state==Player.STATE_BUFFERING) "Buffering video…" else ""
    if(state==Player.STATE_ENDED)ended()
   }
   override fun onVideoSizeChanged(size:VideoSize){if(size.height>0)aspectCallback(size.width*size.pixelWidthHeightRatio/size.height);decoded="Decoded video: ${size.width}x${size.height}";AppLog.add("Video decoded ${size.width}x${size.height}; pixel ratio ${size.pixelWidthHeightRatio}");infoCallback("${if(webFile(file)) "Browser Google stream (browser-supplied quality)" else if(nativeFile(file)) "Phone/provider media (no PhotosBeam transcode)" else "Google-provided transcode"}\n$decoded\n$trackInfo\n$decoderInfo\nPreferred hardware codecs: ${hardwareMimes.joinToString().ifBlank{"none reported"}}\n$outputInfo")}
   override fun onTracksChanged(tracks:Tracks){
    val choices=mutableListOf<Pair<VideoTrackPreference,VideoQualityOption>>()
    val supported=mutableListOf<Triple<Tracks.Group,Int,Format>>()
    for(group in tracks.groups)if(group.type==C.TRACK_TYPE_VIDEO)for(i in 0 until group.length)if(group.isTrackSupported(i)){
     val format=group.getTrackFormat(i);supported+=Triple(group,i,format)
     val label="${format.width}x${format.height} • ${videoCodecLabel(format.sampleMimeType.orEmpty())}"+(if(format.codecs.isNullOrBlank())"" else " (${format.codecs})")+(if(format.frameRate>0)" • ${format.frameRate} fps" else "")+(if(format.bitrate>0) " • ${"%.1f".format(format.bitrate/1_000_000.0)} Mbps" else "")
     val choice=VideoQualityOption(label,group.isTrackSelected(i),format.width,format.height,format.sampleMimeType.orEmpty(),format.codecs.orEmpty()){
      automaticTrack=false
      player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,listOf(i))).build()
      AppLog.add("Video: user selected $label")
     }
     choices+=VideoTrackPreference(hasHardwareDecoder(context,format),PlaybackQuality(format.width,format.height,format.frameRate,format.bitrate),format.sampleMimeType.orEmpty()) to choice
    }
    val best=supported.maxByOrNull{VideoTrackPreference(hasHardwareDecoder(context,it.third),PlaybackQuality(it.third.width,it.third.height,it.third.frameRate,it.third.bitrate),it.third.sampleMimeType.orEmpty())}
    bestTrack.value=best?.let{entry->VideoQualityOption("Automatic • best supported quality",automaticTrack,0,0,"",""){
     automaticTrack=true
     player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(entry.first.mediaTrackGroup,listOf(entry.second))).build()
    }}
    if(!choseMaximumTrack && best!=null){
     choseMaximumTrack=true
     if(!best.first.isTrackSelected(best.second))player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(best.first.mediaTrackGroup,listOf(best.second))).build()
     AppLog.add("Video: best supported track requested ${best.third.sampleMimeType}; ${best.third.width}x${best.third.height}; ${best.third.frameRate} fps; hardware=${hasHardwareDecoder(context,best.third)}")
    }
    suppliedTracks=choices.sortedByDescending{it.first}.map{it.second}

    for(group in tracks.groups)for(i in 0 until group.length)if(group.isTrackSelected(i)){
     val f=group.getTrackFormat(i)
     AppLog.add("Video track ${f.sampleMimeType}; ${f.width}x${f.height}; codecs=${f.codecs}; bitrate=${f.bitrate}")
     if(group.type==C.TRACK_TYPE_VIDEO)trackInfo="Delivered track: ${f.width}x${f.height} • ${f.sampleMimeType} • "+(if(f.bitrate>0) "${"%.1f".format(f.bitrate/1_000_000.0)} Mbps" else "bitrate unknown")+(if(f.frameRate>0) " • ${f.frameRate} fps" else " • frame rate not reported")

    }
    infoCallback("${if(webFile(file)) "Browser Google stream (browser-supplied quality)" else if(nativeFile(file)) "Phone/provider media (no PhotosBeam transcode)" else "Google-provided transcode"}\n$decoded\n$trackInfo\n$decoderInfo\nPreferred hardware codecs: ${hardwareMimes.joinToString().ifBlank{"none reported"}}\n$outputInfo")
   }
   override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int){stateCallback(if(!ready) "Paused" else if(player.playbackState==Player.STATE_READY) "Playing" else "Buffering");if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)interacted()}
   override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)interacted()}
   override fun onPlayerError(error:PlaybackException){if(hasStreamingSeekFailure(error)){
    failureMessage="This Google video needs a seek that the server refuses. Streaming stopped; no full-file download was started. Try the browser helper or a local phone file. Full-file preparation is an explicit slow option in Quality."
    AppLog.add(failureMessage);failed(failureMessage);return
   };failureMessage="Video failed: ${mediaError(error)}. "+(if(generateSequence<Throwable>(error){it.cause}.any{it is java.net.SocketTimeoutException}) "Media delivery timed out before playback could proceed. Try the HTTP/1.1 connection option in Quality & info, or select files on the phone." else "Retry to refresh access, or send TV log to phone.");AppLog.add(failureMessage);failed(failureMessage)}
  }
  player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredVideoMimeTypes(*hardwareMimes.toTypedArray()).setForceHighestSupportedBitrate(true).build()
  player.addAnalyticsListener(object:androidx.media3.exoplayer.analytics.AnalyticsListener{
   override fun onVideoDecoderInitialized(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){decoderInfo="Decoder: $decoderName";AppLog.add("Video decoder initialized: $decoderName (${initializationDurationMs}ms)");infoCallback("$decoded\n$trackInfo\n$decoderInfo\n$outputInfo")}
   override fun onRenderedFirstFrame(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,output:Any,renderTimeMs:Long){AppLog.add("Video: first frame rendered after ${android.os.SystemClock.elapsedRealtime()-startTime}ms")}
  })
  player.addListener(listener)
  player.playWhenReady=true
  val prepareJob=playbackScope.launch{
   try{
    message=if(directFile(file))"Preparing phone video…" else "Resolving Google download…"
    val location=if(directFile(file))null else locateVideoSource(mediaUrl(file,"=dv"),token,resolver!!)
    replayHeaders.value=location?.headers ?: if(webFile(file))emptyMap() else mapOf("Authorization" to "Bearer $token")
    sourceFactory.setDefaultRequestProperties(replayHeaders.value)
    player.setMediaItem(MediaItem.fromUri(location?.url?.toString() ?: mediaUrl(file,"=dv")))
    AppLog.add("Video: player connection ${if(http1Only)"HTTP/1.1 compatibility" else "automatic HTTP/2 or HTTP/1.1"}; hardware-first decoder selection")
    player.prepare()
   }catch(e:CancellationException){throw e}
   catch(e:Exception){failureMessage="Video source failed: ${mediaError(e)}. Retry or use phone media.";AppLog.add(failureMessage);failed(failureMessage);stateCallback("Source connection failed")}
  }
  val progressJob=playbackScope.launch{while(isActive){position=player.currentPosition.coerceAtLeast(0);duration=player.duration.takeIf{it>0} ?: 0L
    preparation=if(player.playbackState==Player.STATE_BUFFERING)replay?.progress()?.let{p->
     fun mib(bytes:Long)="%.0f".format(bytes/1048576.0)
     if(p.oversized && p.requestedOffset>p.total*0.9 && p.total>0)"Reading metadata near the end: ${mib(p.directReceived)} / ${mib(p.total)} MiB. Google ignored seeking. Use a phone file for faster startup."
     else if(p.oversized)"Large Google video: ${mib(p.total)} MiB; TV buffer budget ${mib(p.budget)} MiB. Free TV storage or stream a local phone file for better startup."
     else if(!p.buffered)"Connecting video stream…"
     else if(p.total>0)"Preparing Google video: ${mib(p.received)} / ${mib(p.total)} MiB"
     else "Waiting for Google's video response…"
    }.orEmpty() else ""
    delay(if(chromeNow || player.playbackState==Player.STATE_BUFFERING)500 else 2000)}}
  onDispose{prepareJob.cancel();progressJob.cancel();controlCallback(null);optionsCallback(emptyList());player.removeListener(listener);replay?.close();handoff?.close();player.release()}
 }
 Box(modifier.background(Color.Black)){
  AndroidView(factory={ZoomVideoFrame(it,player)},update={it.updateViewport(viewport)},modifier=Modifier.fillMaxSize())
  if(controlsVisible)Column(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.75f)).padding(horizontal=24.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   if(duration>0)LinearProgressIndicator(progress={ (position.toDouble()/duration).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
   Text("${playbackTime(position)} / ${if(duration>0)playbackTime(duration)else "--:--"}  •  ${if(zoomMode)"D-pad pan  •  OK zoom  •  Back fit" else "↑/↓ item  •  ←/→ seek 10s  •  OK play/pause  •  Hold OK actions"}")
  }
  val feedback=failureMessage.ifBlank{if(message.isNotBlank())listOf(message,preparation).filter{it.isNotBlank()}.joinToString("\n")else ""}
  if(feedback.isNotBlank())Text(feedback,Modifier.align(androidx.compose.ui.Alignment.Center).widthIn(max=620.dp).background(Color.Black.copy(alpha=.8f)).padding(18.dp),color=Color(0xFFE8E8E8),style=MaterialTheme.typography.bodyMedium)
 }
}

/** Prefer resolution, then frame rate, then bitrate among tracks the decoder supports. */
data class PlaybackQuality(val width:Int,val height:Int,val frameRate:Float,val bitrate:Int):Comparable<PlaybackQuality>{
 override fun compareTo(other:PlaybackQuality)=compareValuesBy(this,other,
  {maxOf(0,it.width).toLong()*maxOf(0,it.height)},
  {if(it.frameRate.isFinite())maxOf(0f,it.frameRate)else 0f},
  {maxOf(0,it.bitrate)})
}

fun playbackTime(milliseconds:Long):String {
 val seconds=milliseconds.coerceAtLeast(0)/1000
 return if(seconds>=3600)"${seconds/3600}:${"%02d".format(seconds/60%60)}:${"%02d".format(seconds%60)}" else "${seconds/60}:${"%02d".format(seconds%60)}"
}

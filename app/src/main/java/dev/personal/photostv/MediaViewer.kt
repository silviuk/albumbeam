package dev.personal.photostv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
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
data class VideoQualityOption(val label:String,val selected:Boolean,val choose:()->Unit)
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
     val factor=minOf(canvas.width.toFloat()/image.width,canvas.height.toFloat()/image.height)
     val w=image.width*factor;val h=image.height*factor
     canvas.drawBitmap(image,null,RectF((canvas.width-w)/2,(canvas.height-h)/2,(canvas.width+w)/2,(canvas.height+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
    }
   }finally{holder.unlockCanvasAndPost(canvas)}
  }catch(error:Exception){AppLog.add("Photo surface draw failed: ${error.javaClass.simpleName}")}
 }
}

@Composable fun PhotoViewer(file:JSONObject,token:String,loader:ImageLoader,modifier:Modifier,quality:PhotoQuality,onInfo:(String)->Unit,onLoaded:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val display=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
 val mode=display.supportedModes.maxByOrNull{it.physicalWidth.toLong()*it.physicalHeight} ?: display.mode
 val current=display.mode
 val multiplier=maxOf(1.0,mode.physicalWidth.toDouble()/current.physicalWidth,mode.physicalHeight.toDouble()/current.physicalHeight)
 // Keep the current display aspect ratio even if a wider cinema mode is supported.
 val pixels=qualityPixels(photoPixels((current.physicalWidth*multiplier).toInt(),(current.physicalHeight*multiplier).toInt()),quality)
 val surface=remember(quality){PhotoSurface(context,pixels)}
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
   .data(photoRequest(file.getString("baseUrl"),pixels,quality))
   .addHeader("Authorization","Bearer $token")
   .size(pixels.width,pixels.height).scale(Scale.FIT).precision(Precision.EXACT)
   .allowHardware(false).bitmapConfig(Bitmap.Config.ARGB_8888)
   .diskCachePolicy(CachePolicy.DISABLED)
   .memoryCacheKey("full:${file.getString("baseUrl")}:${pixels.width}x${pixels.height}:$quality:${token.hashCode()}").build()
  val result=loader.execute(request)
  loading=false
  if(result is SuccessResult){
   val image=(result.drawable as? BitmapDrawable)?.bitmap
   if(image!=null){surface.bitmap=image;surface.drawPhoto();AppLog.add("Photo decoded ${image.width}x${image.height}; ${image.byteCount/1024} KiB; ${result.dataSource}");infoCallback("${quality.label}\nDecoded photo: ${image.width}x${image.height}\nSurface target: ${pixels.width}x${pixels.height}\nDisplay output: ${current.physicalWidth}x${current.physicalHeight}\nFull-size files are decoded to a bounded TV resolution; no cropping.");loadedCallback()}
   else{failure="Photo format could not be rendered.";failedCallback(failure)}
  }else if(result is ErrorResult){failure="Photo failed: ${mediaError(result.throwable)}. Retry to refresh access.";AppLog.add(failure);failedCallback(failure)}
 }
 DisposableEffect(surface){onDispose{surface.bitmap=null}}
 Box(modifier.background(Color.Black)){
  AndroidView(factory={surface},modifier=Modifier.fillMaxSize())
  if(loading)Text("Loading full-resolution photo…",modifier=Modifier.padding(top=100.dp,start=24.dp))
  if(failure.isNotBlank())Text(failure,modifier=Modifier.padding(top=100.dp,start=24.dp))
 }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun VideoViewer(file:JSONObject,token:String,modifier:Modifier,fastStartup:Boolean,controlsVisible:Boolean,onActivity:(Boolean)->Unit,onControlReady:(((String)->Unit)?)->Unit,onState:(String)->Unit,onInfo:(String)->Unit,onOptions:(List<VideoQualityOption>)->Unit,onEnded:()->Unit,onInteraction:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val chromeNow by rememberUpdatedState(controlsVisible);val activityCallback by rememberUpdatedState(onActivity);val controlCallback by rememberUpdatedState(onControlReady);val stateCallback by rememberUpdatedState(onState)
 val infoCallback by rememberUpdatedState(onInfo);val optionsCallback by rememberUpdatedState(onOptions)
 var decoded by remember{mutableStateOf("Decoded resolution: waiting for playback")};var trackInfo by remember{mutableStateOf("")}
 val ended by rememberUpdatedState(onEnded);val interacted by rememberUpdatedState(onInteraction);val failed by rememberUpdatedState(onFailure)
 val outputMode=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.mode
 val outputInfo="Display output: ${outputMode.physicalWidth}x${outputMode.physicalHeight}"
 val processing=videoReadiness(file)
 var message by remember{mutableStateOf("Preparing video…")}
 if(processing=="PROCESSING" || processing=="FAILED"){
  val reason=if(processing=="PROCESSING") "Google Photos is still processing this video. Retry later." else "Google Photos could not process this video."
  LaunchedEffect(reason){AppLog.add("Video status: $processing");failed(reason)}
  Box(modifier.background(Color.Black)){Text(reason,Modifier.padding(top=100.dp,start=24.dp))}
  return
 }
 val player=remember{
  AppLog.add("Video: preparing progressive playback; processing=${processing.ifBlank{"unspecified"}}; MIME=${file.optString("mimeType")}")
  val http=videoHttpClient()
  val source=OkHttpDataSource.Factory(http).setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token")).setUserAgent("AlbumBeam/${BuildConfig.VERSION_NAME}")
  ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true))
   .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000,20000,750,1500).setPrioritizeTimeOverSizeThresholds(true).build())
   .setMediaSourceFactory(DefaultMediaSourceFactory(source,streamingExtractors(fastStartup)).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1))).build()
 }
 DisposableEffect(player){
  val startTime=android.os.SystemClock.elapsedRealtime()
  AppLog.add("Video: direct Google streaming; fast startup=$fastStartup; startup buffer 750ms; no local transcode")
  controlCallback{action->when(action){"play"->{if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()};"pause"->player.pause()}}
  val listener=object:Player.Listener{
   override fun onPlaybackStateChanged(state:Int){
    AppLog.add("Video playback state: "+when(state){Player.STATE_BUFFERING->"BUFFERING";Player.STATE_READY->"READY";Player.STATE_ENDED->"ENDED";else->"IDLE"})
    stateCallback(when(state){Player.STATE_BUFFERING->"Buffering";Player.STATE_READY->if(player.playWhenReady) "Playing" else "Paused";Player.STATE_ENDED->"Ended";else->"Idle"})
    message=if(state==Player.STATE_BUFFERING) "Buffering video…" else ""
    if(state==Player.STATE_ENDED)ended()
   }
   override fun onVideoSizeChanged(size:VideoSize){decoded="Decoded video: ${size.width}x${size.height}";AppLog.add("Video decoded ${size.width}x${size.height}; pixel ratio ${size.pixelWidthHeightRatio}");infoCallback("Google-provided transcode\n$decoded\n$trackInfo\n$outputInfo")}
   override fun onTracksChanged(tracks:Tracks){
    val choices=mutableListOf<VideoQualityOption>()
    for(group in tracks.groups)if(group.type==C.TRACK_TYPE_VIDEO)for(i in 0 until group.length)if(group.isTrackSupported(i)){
     val format=group.getTrackFormat(i)
     val label="${format.width}x${format.height}"+(if(format.bitrate>0) " • ${"%.1f".format(format.bitrate/1_000_000.0)} Mbps" else "")
     choices+=VideoQualityOption(label,group.isTrackSelected(i)){
      player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,listOf(i))).build()
      AppLog.add("Video: user selected $label")
     }
    }
    optionsCallback(choices)

    for(group in tracks.groups)for(i in 0 until group.length)if(group.isTrackSelected(i)){
     val f=group.getTrackFormat(i)
     AppLog.add("Video track ${f.sampleMimeType}; ${f.width}x${f.height}; codecs=${f.codecs}; bitrate=${f.bitrate}")
     if(group.type==C.TRACK_TYPE_VIDEO)trackInfo="Delivered track: ${f.width}x${f.height} • ${f.sampleMimeType} • "+(if(f.bitrate>0) "${"%.1f".format(f.bitrate/1_000_000.0)} Mbps" else "bitrate unknown")+(if(f.frameRate>0) " • ${f.frameRate} fps" else "")

    }
    infoCallback("Google-provided transcode\n$decoded\n$trackInfo\n$outputInfo")
   }
   override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int){stateCallback(if(!ready) "Paused" else if(player.playbackState==Player.STATE_READY) "Playing" else "Buffering");if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)interacted()}
   override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)interacted()}
   override fun onPlayerError(error:PlaybackException){message="Video failed: ${mediaError(error)}. "+(if(generateSequence<Throwable>(error){it.cause}.any{it is java.net.SocketTimeoutException}) "Google video delivery timed out before playback could proceed. Retry and share the TV log." else "Retry to refresh access, or send TV log to phone.");AppLog.add(message);failed(message)}
  }
  player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(true).build()
  player.addAnalyticsListener(object:androidx.media3.exoplayer.analytics.AnalyticsListener{
   override fun onVideoDecoderInitialized(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){AppLog.add("Video decoder initialized: $decoderName (${initializationDurationMs}ms)")}
   override fun onRenderedFirstFrame(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,output:Any,renderTimeMs:Long){AppLog.add("Video: first frame rendered after ${android.os.SystemClock.elapsedRealtime()-startTime}ms")}
  })
  player.addListener(listener)
  player.setMediaItem(MediaItem.fromUri(file.getString("baseUrl")+"=dv"));player.prepare();player.playWhenReady=true
  onDispose{controlCallback(null);player.removeListener(listener);player.release()}
 }
 Box(modifier.background(Color.Black)){
  AndroidView(factory={PlayerView(it).apply{
   this.player=player;useController=true;controllerShowTimeoutMs=3000;controllerAutoShow=false
   setOnKeyListener{_,code,event->
    if(event.action==android.view.KeyEvent.ACTION_DOWN && code!=android.view.KeyEvent.KEYCODE_BACK){
     val hidden=!chromeNow;val navigation=code in listOf(19,20,21,22,23,66)
     activityCallback(hidden && navigation)
     hidden && navigation
    }else false
   }
   requestFocus()
  }},update={view->if(!controlsVisible){view.hideController();if(!view.hasFocus())view.requestFocus()}},modifier=Modifier.fillMaxSize())
  if(message.isNotBlank())Text(message,Modifier.padding(top=100.dp,start=24.dp))
 }
}

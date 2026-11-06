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

@Composable fun PhotoViewer(file:JSONObject,token:String,loader:ImageLoader,modifier:Modifier,onLoaded:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val display=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
 val mode=display.supportedModes.maxByOrNull{it.physicalWidth.toLong()*it.physicalHeight} ?: display.mode
 val current=display.mode
 val multiplier=maxOf(1.0,mode.physicalWidth.toDouble()/current.physicalWidth,mode.physicalHeight.toDouble()/current.physicalHeight)
 // Keep the current display aspect ratio even if a wider cinema mode is supported.
 val pixels=photoPixels((current.physicalWidth*multiplier).toInt(),(current.physicalHeight*multiplier).toInt())
 val surface=remember{PhotoSurface(context,pixels)}
 var failure by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(true)}
 val loadedCallback by rememberUpdatedState(onLoaded)
 val failedCallback by rememberUpdatedState(onFailure)
 LaunchedEffect(file.optString("baseUrl"),token){
  loading=true;surface.bitmap=null;surface.drawPhoto()
  val meta=file.optJSONObject("mediaFileMetadata")
  AppLog.add("Photo request ${pixels.width}x${pixels.height}; source ${meta?.optInt("width")}x${meta?.optInt("height")}; display mode ${display.mode.physicalWidth}x${display.mode.physicalHeight}")
  val request=ImageRequest.Builder(context)
   .data(file.getString("baseUrl")+"=w${pixels.width}-h${pixels.height}")
   .addHeader("Authorization","Bearer $token")
   .size(pixels.width,pixels.height).scale(Scale.FIT).precision(Precision.EXACT)
   .allowHardware(false).bitmapConfig(Bitmap.Config.ARGB_8888)
   .diskCachePolicy(CachePolicy.DISABLED)
   .memoryCacheKey("full:${file.getString("baseUrl")}:${pixels.width}x${pixels.height}:${token.hashCode()}").build()
  val result=loader.execute(request)
  loading=false
  if(result is SuccessResult){
   val image=(result.drawable as? BitmapDrawable)?.bitmap
   if(image!=null){surface.bitmap=image;surface.drawPhoto();AppLog.add("Photo decoded ${image.width}x${image.height}; ${image.byteCount/1024} KiB; ${result.dataSource}");loadedCallback()}
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
@Composable fun VideoViewer(file:JSONObject,token:String,modifier:Modifier,onEnded:()->Unit,onInteraction:()->Unit,onFailure:(String)->Unit){
 val context=LocalContext.current
 val ended by rememberUpdatedState(onEnded);val interacted by rememberUpdatedState(onInteraction);val failed by rememberUpdatedState(onFailure)
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
  val http=OkHttpClient.Builder().followRedirects(true).followSslRedirects(false)
   .connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS)
   .addNetworkInterceptor{chain->val response=chain.proceed(chain.request());AppLog.add("Video HTTP ${response.code}; content-type=${response.header("Content-Type")}; content-length=${response.header("Content-Length")}; ranged=${chain.request().header("Range")!=null}");response}.build()
  val source=OkHttpDataSource.Factory(http).setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token")).setUserAgent("AlbumBeam/${BuildConfig.VERSION_NAME}")
  ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true)).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
 }
 DisposableEffect(player){
  val listener=object:Player.Listener{
   override fun onPlaybackStateChanged(state:Int){
    AppLog.add("Video playback state: "+when(state){Player.STATE_BUFFERING->"BUFFERING";Player.STATE_READY->"READY";Player.STATE_ENDED->"ENDED";else->"IDLE"})
    message=if(state==Player.STATE_BUFFERING) "Buffering video…" else ""
    if(state==Player.STATE_ENDED)ended()
   }
   override fun onVideoSizeChanged(size:VideoSize){AppLog.add("Video decoded ${size.width}x${size.height}; pixel ratio ${size.pixelWidthHeightRatio}")}
   override fun onTracksChanged(tracks:Tracks){
    for(group in tracks.groups)for(i in 0 until group.length)if(group.isTrackSelected(i)){
     val f=group.getTrackFormat(i)
     AppLog.add("Video track ${f.sampleMimeType}; ${f.width}x${f.height}; codecs=${f.codecs}; bitrate=${f.bitrate}")
    }
   }
   override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int){if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)interacted()}
   override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)interacted()}
   override fun onPlayerError(error:PlaybackException){message="Video failed: ${mediaError(error)}. Retry to refresh access, or send TV log to phone.";AppLog.add(message);failed(message)}
  }
  player.addListener(listener)
  player.setMediaItem(MediaItem.fromUri(file.getString("baseUrl")+"=dv"));player.prepare();player.playWhenReady=true
  onDispose{player.removeListener(listener);player.release()}
 }
 Box(modifier.background(Color.Black)){
  AndroidView(factory={PlayerView(it).apply{this.player=player;useController=true;requestFocus()}},modifier=Modifier.fillMaxSize())
  if(message.isNotBlank())Text(message,Modifier.padding(top=100.dp,start=24.dp))
 }
}

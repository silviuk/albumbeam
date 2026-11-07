package dev.personal.photostv

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.net.ssl.SSLContext

/** A single byte range; reject multipart ranges instead of returning misleading data. */
data class ByteRange(val start:Long,val end:Long){
 companion object {
  fun parse(value:String,length:Long):ByteRange {
   require(length>0 && value.startsWith("bytes=") && !value.contains(','))
   val parts=value.removePrefix("bytes=").split('-');require(parts.size==2)
   val start:Long;val end:Long
   if(parts[0].isEmpty()){val count=parts[1].toLong();require(count>0);start=maxOf(0,length-count);end=length-1}
   else{start=parts[0].toLong();end=if(parts[1].isEmpty())length-1 else minOf(parts[1].toLong(),length-1)}
   require(start>=0 && start<length && end>=start);return ByteRange(start,end)
  }
 }
}
class NativeMediaServer(private val context:Context,private val uris:List<Uri>,private val encrypted:Boolean=true):NanoHTTPD(0){
 private val device=DeviceIdentity.create()
 private val grant=secret()
 private val pin=fingerprint(device.held.certificate.encoded)
 init{
  val manager=PairingKeyManager("phone-media",device.held.keyPair.private,arrayOf(device.held.certificate)){}
  val ssl=SSLContext.getInstance("TLS").apply{init(arrayOf(manager),null,SecureRandom())}
  if(encrypted)makeSecure(ssl.serverSocketFactory,arrayOf("TLSv1.2"))
 }
 fun selection(host:String=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{it is Inet4Address && it.isSiteLocalAddress}?.hostAddress ?: error("Connect the phone to your home network")):JSONObject {
  val list=JSONArray()
  uris.forEachIndexed{index,uri->
   val mime=(context.contentResolver.getType(uri) ?: java.net.URLConnection.guessContentTypeFromName(uri.lastPathSegment)).orEmpty()
   require(mime.startsWith("image/") || mime.startsWith("video/")){"Unsupported selected media type"}
   val metadata=JSONObject()
   // Optional provider metadata. No full library access is requested.
   runCatching{context.contentResolver.query(uri,arrayOf("width","height"),null,null,null)?.use{cursor->if(cursor.moveToFirst()){
    for(column in listOf("width","height")){val i=cursor.getColumnIndex(column);if(i>=0 && !cursor.isNull(i))metadata.put(column,cursor.getInt(i))}
   }}}
   list.put(JSONObject().put("id",index.toString()).put("type",if(mime.startsWith("video/"))"VIDEO" else "PHOTO")
    .put("mediaFile",JSONObject().put("baseUrl","${if(encrypted)"https" else "http"}://$host:$listeningPort/media/$index").put("nativePin",pin).put("mimeType",mime).put("mediaFileMetadata",metadata)))
  }
  return JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("session","native:"+secret()).put("token",grant).put("source","native").put("items",list)
 }
 override fun serve(session:IHTTPSession):Response {
  if(session.headers["authorization"]!="Bearer $grant")return newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Rejected")
  if(session.uri=="/stop" && session.method==Method.POST){context.stopService(Intent(context,PhoneMediaService::class.java));return newFixedLengthResponse("{}")}
  if(session.method!=Method.GET && session.method!=Method.HEAD)return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,"text/plain","")
  val index=session.uri.removePrefix("/media/").toIntOrNull()
  val uri=if(session.uri.startsWith("/media/") && index!=null)uris.getOrNull(index) else null
  if(uri==null)return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","")
  var opened:java.io.Closeable?=null
  return try{
   val descriptor=(if(android.os.Build.VERSION.SDK_INT>=31 && uri.scheme=="content")context.contentResolver.openTypedAssetFileDescriptor(uri,"*/*",android.os.Bundle().apply{putBoolean(android.provider.MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT,true)}) else context.contentResolver.openAssetFileDescriptor(uri,"r")) ?: error("Media unavailable")
   opened=descriptor
   val length=if(descriptor.length>=0)descriptor.length else descriptor.parcelFileDescriptor.statSize
   val rangeHeader=session.headers["range"]
   val range=if(rangeHeader!=null && length>=0)try{ByteRange.parse(rangeHeader,length)}catch(e:Exception){descriptor.close();return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,"text/plain","").apply{addHeader("Content-Range","bytes */$length")}} else null
   val stream=descriptor.createInputStream();opened=stream
   if(range!=null){var remaining=range.start;while(remaining>0){val moved=stream.skip(remaining);if(moved<=0){if(stream.read()<0)error("Unable to seek");remaining--}else remaining-=moved}}
   val mime=context.contentResolver.getType(uri) ?: "application/octet-stream"
   val count=range?.let{it.end-it.start+1} ?: length
   val response=if(count>=0)newFixedLengthResponse(if(range==null)Response.Status.OK else Response.Status.PARTIAL_CONTENT,mime,stream,count) else newChunkedResponse(Response.Status.OK,mime,stream)
   response.addHeader("Cache-Control","no-store")
   if(length>=0)response.addHeader("Accept-Ranges","bytes")
   if(range!=null)response.addHeader("Content-Range","bytes ${range.start}-${range.end}/$length")
   AppLog.add("Phone media: ${if(range==null) "full" else "range"} request; bytes=$count; type=$mime")
   opened=null // NanoHTTPD owns and closes the response stream.
   response
  }catch(e:Exception){runCatching{opened?.close()};AppLog.add("Phone media: provider read failed (${e.javaClass.simpleName})");newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,"text/plain","Provider media unavailable")}
 }
}

/** Started only by an explicit selection while the companion is visible. Never restarted after reboot. */
data class NativeShareRequest(val uris:List<Uri>,val encrypted:Boolean,val result:CompletableDeferred<JSONObject>)
class PhoneMediaService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 @Volatile private var server:NativeMediaServer?=null
 private var preparing:Job?=null
 companion object {
  @Volatile var pending:NativeShareRequest?=null
  @Volatile var running=false
 }
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
  val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  manager.createNotificationChannel(NotificationChannel("media-sharing","TV media sharing",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,2,Intent(this,PhoneMediaService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(this,"media-sharing").setSmallIcon(dev.personal.photostv.R.drawable.ic_albumbeam).setContentTitle("AlbumBeam is sharing with your TV")
   .setContentText("Keep this phone connected. Tap Stop to end media access.").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
  if(android.os.Build.VERSION.SDK_INT>=29)startForeground(10,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(10,notification)
  val request=pending;pending=null
  if(request==null){stopSelf();return START_NOT_STICKY}
  preparing?.cancel()
  preparing=scope.launch{
   var candidate:NativeMediaServer?=null
   try{
    server?.stop();candidate=NativeMediaServer(this@PhoneMediaService,request.uris,request.encrypted)
    ensureActive();server=candidate;candidate.start()
    val payload=candidate.selection();ensureActive();running=true
    request.result.complete(payload)
    AppLog.add("Phone: native media sharing started (${request.uris.size} items); ${if(request.encrypted)"HTTPS" else "HTTP"}; bytes unchanged")
   }catch(e:Exception){candidate?.stop();request.result.completeExceptionally(e);if(e !is CancellationException)stopSelf()}
  }
  return START_NOT_STICKY
 }
 override fun onDestroy(){running=false;server?.stop();scope.cancel();AppLog.add("Phone: native media access revoked");super.onDestroy()}
}
fun nativeFile(file:JSONObject)=file.optString("nativePin").isNotBlank()
fun mediaUrl(file:JSONObject,suffix:String)=file.getString("baseUrl")+if(nativeFile(file))"" else suffix
fun mediaClient(file:JSONObject):okhttp3.OkHttpClient {
 if(!nativeFile(file))return videoHttpClient()
 val uri=java.net.URI(file.getString("baseUrl"))
 require(uri.host!=null && uri.port in 1..65535 && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null)
 require(uri.scheme=="https" || (uri.scheme=="http" && privateLanHost(uri.host))){"Plain HTTP media requires a private home-network address"}
 if(uri.scheme=="http")return okhttp3.OkHttpClient.Builder().followRedirects(false).connectTimeout(4,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build()
 return Connection(Link(uri.host,uri.port,file.getString("nativePin"),"")).client.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build()
}

/** Numeric addresses only: never resolve untrusted DNS while authorizing cleartext media. */
fun privateLanHost(host:String):Boolean {
 val parts=host.split('.').map{it.toIntOrNull() ?: return false}
 if(parts.size!=4 || parts.any{it !in 0..255})return false
 return parts[0]==10 || (parts[0]==172 && parts[1] in 16..31) || (parts[0]==192 && parts[1]==168) || parts[0]==127
}

fun mediaTransport(file:JSONObject)=if(!nativeFile(file))"Google HTTPS" else if(file.getString("baseUrl").startsWith("http://"))"Phone HTTP (unencrypted)" else "Phone HTTPS (encrypted)"

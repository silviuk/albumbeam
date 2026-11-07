package dev.personal.photostv

import android.app.*
import android.content.*
import android.os.IBinder
import kotlinx.coroutines.*

/** Phone-only foreground service; no service or related permissions are installed on TV. */
class PhoneMediaService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 @Volatile private var server:NativeMediaServer?=null
 private var preparing:Job?=null
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
  val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  manager.createNotificationChannel(NotificationChannel("media-sharing","TV media sharing",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,2,Intent(this,PhoneMediaService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(this,"media-sharing").setSmallIcon(dev.personal.photostv.R.drawable.ic_albumbeam).setContentTitle("PhotosBeam is sharing with your TV")
   .setContentText("Keep this phone connected. Tap Stop to end media access.").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
  if(android.os.Build.VERSION.SDK_INT>=29)startForeground(10,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(10,notification)
  val request=NativeSharing.pending;NativeSharing.pending=null
  if(request==null){stopSelf();return START_NOT_STICKY}
  preparing?.cancel()
  preparing=scope.launch{
   var candidate:NativeMediaServer?=null
   try{
    server?.stop();candidate=NativeMediaServer(this@PhoneMediaService,request.uris,request.encrypted)
    ensureActive();server=candidate;candidate.start()
    val payload=candidate.selection();ensureActive();NativeSharing.running=true
    request.result.complete(payload)
    AppLog.add("Phone: native media sharing started (${request.uris.size} items); ${if(request.encrypted)"HTTPS" else "HTTP"}; bytes unchanged")
   }catch(e:Exception){candidate?.stop();request.result.completeExceptionally(e);if(e !is CancellationException)stopSelf()}
  }
  return START_NOT_STICKY
 }
 override fun onDestroy(){NativeSharing.running=false;server?.stop();scope.cancel();AppLog.add("Phone: native media access revoked");super.onDestroy()}
}

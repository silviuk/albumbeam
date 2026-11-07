package dev.personal.photostv

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.NetworkInterface
import java.time.Instant
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity:ComponentActivity() {
 var status by mutableStateOf("Ready")
 var qr by mutableStateOf<Bitmap?>(null)
 var items by mutableStateOf<List<JSONObject>>(emptyList())
 var epoch by mutableIntStateOf(0)
 var token by mutableStateOf("")
 private val pairingStore by lazy{PairingStore(this)}
 private var reconnectJob:Job?=null
 private var encryptMedia by mutableStateOf(true)
 private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
 private var nativeBusy by mutableStateOf(false)
 private var hasSavedPairing by mutableStateOf(false)
 private val nearby by lazy{NearbyPairing(this)}
 private var manualDisplay by mutableStateOf("")
 private var verificationDisplay by mutableStateOf("")
 private var photoWarmth by mutableFloatStateOf(0f)
 private var remoteViewportHandler:((ViewportChange)->Unit)?=null
 private fun cycleWarmth(){photoWarmth=nextWarmth(photoWarmth);getSharedPreferences("settings",MODE_PRIVATE).edit().putFloat("photo-warmth",photoWarmth).apply()}
 private var remoteCommandHandler:((RemoteAction)->Unit)?=null
 private var videoControl:((String)->Unit)?=null
 @Volatile private var remoteSummary="No media selected"
 private var tvState by mutableStateOf("")
 private fun sendControl(action:RemoteAction){task{
  val c=connection ?: error("Pair with your TV first")
  withContext(Dispatchers.IO){c.post("control",JSONObject().put("action",action.wire).put("requestId",UUID.randomUUID().toString()))}
  AppLog.add("Phone: TV command sent (${action.wire})")
 }}
 private var pickerLink by mutableStateOf("")
 private var pickerOnComputer=false
 private var pickerInEdge=false
 private var edgeUnavailable by mutableStateOf(false)
 private var desktopDialog by mutableStateOf(false)
 private var authorizationBusy by mutableStateOf(false)
 private var selectionStep by mutableStateOf("")
 private var selectionError by mutableStateOf("")
 private fun selectionFailure(message:String,kind:String="unknown"){
  authorizationBusy=false;token="";status=message;selectionError=message
  AppLog.add("Phone: selection preparation failed ($kind)")
 }
 private var tvReport by mutableStateOf("")
 private var logReceiver:Job?=null
 @Volatile var session=""; var expires=Instant.MAX; var server:PairServer?=null; var connection by mutableStateOf<Connection?>(null)
 var job:Job?=null; var renew=false
 private var networkWatch:Job?=null
 private fun localAddress()=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{it is java.net.Inet4Address && it.isSiteLocalAddress}?.hostAddress
 private val zoomResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==RESULT_OK)result.data?.getStringExtra("qr")?.let{pairQr(it)}
 }
 private val nativePicker=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(if(android.os.Build.VERSION.SDK_INT>=33)minOf(2000,android.provider.MediaStore.getPickImagesMaxLimit())else 100)){uris->shareNative(uris)}
 private val filePicker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->shareNative(uris)}
 private fun shareNative(uris:List<Uri>){
  if(nativeBusy)return
  if(uris.isEmpty()){status="Selection cancelled";return}
  if(uris.size>2000){status="Choose at most 2,000 items per session";return}
  task{
   nativeBusy=true
   try{
    val c=connection ?: error("Connect to the TV first")
    val request=NativeShareRequest(uris,encryptMedia,CompletableDeferred())
    NativeSharing.pending=request
    startForegroundService(NativeSharing.intent(this))
    status="Preparing selected phone/cloud media…"
    val payload=withTimeout(60000){request.result.await()}
    withContext(Dispatchers.IO){c.post("session",payload)}
    session=""
    status="Sharing ${uris.size} items. Keep the phone connected; cloud items may need downloading by their provider."
   }catch(e:Exception){stopService(NativeSharing.intent(this));throw e}
   finally{NativeSharing.pending=null;nativeBusy=false}
  }
 }
 @Suppress("DEPRECATION")
 private fun receiveSharedMedia(incoming:Intent?){
  if(BuildConfig.FLAVOR!="phone" || incoming?.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
  val found=mutableListOf<Uri>()
  if(incoming?.action==Intent.ACTION_SEND)incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{found+=it}
  if(incoming?.action==Intent.ACTION_SEND_MULTIPLE)incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let{found+=it}
  incoming?.clipData?.let{clip->for(i in 0 until clip.itemCount)clip.getItemAt(i).uri?.let{found+=it}}
  sharedUris=found.filter{it.scheme=="content"}.distinct().take(2001)
  status=if(sharedUris.isEmpty())"The gallery shared a link rather than media files. Download the files first, then share them or choose files in PhotosBeam." else "${sharedUris.size} gallery items ready. Connect to your TV, then send them below."
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);receiveSharedMedia(intent)}
 private fun restorePairing(){
  if(BuildConfig.FLAVOR!="phone" || reconnectJob?.isActive==true)return
  val saved=pairingStore.read("phone") ?: return
  hasSavedPairing=true;status="Reconnecting to your saved TV…"
  val link=Link.parse(saved.getJSONObject("link").toString());val auth=saved.getString("auth")
  val candidates=mutableListOf(link)
  runCatching{nearby.discover{peer->if(peer.link.pin==link.pin && candidates.none{it.host==peer.link.host && it.port==peer.link.port})candidates.add(peer.link)}}
  reconnectJob=lifecycleScope.launch{
   while(isActive && connection==null){
    for(candidate in candidates.toList()){
     if(connection!=null)break
     val c=Connection(candidate).apply{this.auth=auth}
     val ok=withContext(Dispatchers.IO){runCatching{c.post("log-inbox",JSONObject());true}.getOrDefault(false)}
     if(ok){connected(c);status="Reconnected to your TV";AppLog.add("Phone: saved pairing authenticated");break}
    }
    if(connection==null){status="Saved TV offline. Open PhotosBeam on your TV; reconnecting automatically…";delay(5000)}
   }
  }
 }
 private fun forgetPairing(){
  reconnectJob?.cancel();logReceiver?.cancel();nearby.stopDiscovery();connection=null;hasSavedPairing=false
  stopService(NativeSharing.intent(this))
  pairingStore.clear(if(BuildConfig.FLAVOR=="tv")"tv" else "phone")
  if(BuildConfig.FLAVOR=="tv"){end(false);pairing()}else status="Pairing forgotten. Forget this phone on the TV before pairing again."
  AppLog.add("Device pairing removed locally")
 }
 private fun connected(c:Connection){
  pairingStore.save("phone",JSONObject().put("link",JSONObject(c.link.copy(code="").json())).put("auth",c.auth))
  hasSavedPairing=true;nearby.stopDiscovery()
  connection=c;tvReport="";logReceiver?.cancel()
  logReceiver=lifecycleScope.launch{
   var serial=0L;var failures=0
   while(isActive){
    delay(5000)
    try{
     val reply=withContext(Dispatchers.IO){c.post("log-inbox",JSONObject().put("after",serial))}
     if(session.isBlank())session=reply.optString("session")
     if(NativeSharing.running && !nativeBusy && !reply.optBoolean("nativeSharing"))stopService(NativeSharing.intent(this@MainActivity))
     failures=0;serial=reply.optLong("serial",serial)
     tvState=reply.optString("state")
     val report=reply.optString("report")
     if(report.isNotBlank()){tvReport=report;AppLog.add("Phone: TV diagnostic report received")}
    }catch(e:Exception){if(e is CancellationException)throw e;failures++;if(failures>=2){connection=null;restorePairing();return@launch};delay(5000)}
   }
  }
 }
 private fun pairQr(raw:String){task{val c=Connection(Link.parse(raw));withContext(Dispatchers.IO){c.pair()};connected(c);AppLog.add("Phone: QR pairing accepted");status="Paired. Select photos to begin."}}
 private val authResult=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
  try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)) } catch(e:Exception){selectionFailure("Authorization cancelled or denied. Try again.",e.javaClass.simpleName)}
 }
 override fun onCreate(b:Bundle?) {
  super.onCreate(b)
  java.io.File(cacheDir,"video-replay").listFiles()?.forEach{it.delete()};photoWarmth=getSharedPreferences("settings",MODE_PRIVATE).getFloat("photo-warmth",0f).coerceIn(0f,1f); encryptMedia=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("encrypt-media",true); if(BuildConfig.FLAVOR=="phone")enableEdgeToEdge(); AppLog.add("Started ${BuildConfig.FLAVOR} app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  setContent {
   val dark=isSystemInDarkTheme()
   val colors=if(BuildConfig.FLAVOR=="tv")darkColorScheme(background=Color.Black,surface=Color(0xFF0D0D0F),primary=Color(0xFFE0D3BD),onSurface=Color(0xFFDADADA)) else if(android.os.Build.VERSION.SDK_INT>=31){if(dark)dynamicDarkColorScheme(this)else dynamicLightColorScheme(this)}else if(dark)darkColorScheme()else lightColorScheme()
   MaterialTheme(colorScheme=colors){
    if(BuildConfig.FLAVOR=="tv")androidx.tv.material3.MaterialTheme(colorScheme=androidx.tv.material3.darkColorScheme(background=Color.Black,surface=Color(0xFF0D0D0F),primary=Color(0xFFE0D3BD),onSurface=Color(0xFFDADADA))){Box(Modifier.fillMaxSize().background(Color.Black)){TV()}}else Surface(Modifier.fillMaxSize()){Phone()}
   }
  }
  if(BuildConfig.FLAVOR=="tv") {
   val display=(getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
   display.supportedModes.filter{maxOf(it.physicalWidth,it.physicalHeight)>=3840 && minOf(it.physicalWidth,it.physicalHeight)>=2160}
    .minWithOrNull(compareBy<android.view.Display.Mode>{kotlin.math.abs(it.refreshRate-60f)}.thenByDescending{it.physicalWidth.toLong()*it.physicalHeight})?.let{mode->
     window.attributes=window.attributes.apply{preferredDisplayModeId=mode.modeId}
     AppLog.add("TV: preferred display ${mode.physicalWidth}x${mode.physicalHeight} at ${mode.refreshRate} Hz (system may retain another mode)")
    }
   task{val capabilities=withContext(Dispatchers.Default){decoderCapabilities()};AppLog.add(capabilities)}
   pairing()
   networkWatch=lifecycleScope.launch { var previous=localAddress(); while(isActive){delay(3000);val next=localAddress();if(next!=previous){previous=next;end();status="Network changed. Paired phone will rediscover this TV."}} }
  }else{restorePairing();receiveSharedMedia(intent)}
 }
 fun task(block:suspend ()->Unit) { lifecycleScope.launch { try{block()}catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "Connection failed. Retry when online."; AppLog.add("Operation failed (${e.javaClass.simpleName}); see screen for details")} } }
 fun pairing() {
  nearby.stopAdvertising();server?.stop()
  val saved=pairingStore.read("tv")
  val restored=saved?.let{runCatching{DeviceIdentity.parse(it)}.getOrNull()}
  val device=restored ?: DeviceIdentity.create()
  hasSavedPairing=restored!=null && saved?.optBoolean("paired")==true
  pairingStore.save("tv",device.json().put("paired",hasSavedPairing))
  server=PairServer(device=device,initiallyPaired=hasSavedPairing,onPaired={pairingStore.save("tv",device.json().put("paired",true));runOnUiThread{hasSavedPairing=true;qr=null;status="Phone paired. Choose media on your phone."}},diagnostic={message -> runOnUiThread{status=message.take(450)}},control={action->runOnUiThread{remoteCommandHandler?.invoke(action)}},viewport={input->runOnUiThread{remoteViewportHandler?.invoke(input)}},state={remoteSummary},nativeSharing={session.startsWith("native:")},selectionId={if(session.startsWith("native:"))"" else session}) { j -> runOnUiThread { AppLog.add("TV: received media selection from companion"); val next=j.getString("session"); if(session.isNotBlank() && next!=session) end(false); token=j.getString("token"); session=next; qr=null
   if(j.optString("source")=="native"){
    job?.cancel();val list=j.getJSONArray("items");items=(0 until list.length()).map{list.getJSONObject(it)}
    require(items.size<=2000);status="${items.size} phone/provider items • Keep phone connected";AppLog.add("TV: native selection loaded (${items.size} items)")
   }else load() } }
  try { server!!.start(); AppLog.add("TV: secure pairing server started; QR valid for five minutes"); val host=localAddress() ?: error("Connect the TV to your home network, then retry.")
   manualDisplay=server!!.manualCode;verificationDisplay=verification(server!!.pin)
   runCatching{nearby.advertise(server!!.listeningPort,server!!.pin)}.onFailure{AppLog.add("TV: discovery unavailable; QR still works")}
   val matrix=MultiFormatWriter().encode(server!!.link(host).json(),BarcodeFormat.QR_CODE,600,600)
   qr=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply { for(x in 0..599) for(y in 0..599) setPixel(x,y,if(matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
   if(hasSavedPairing){qr=null;status="Paired phone remembered. Open PhotosBeam on your phone to reconnect."}else status="Scan using the PhotosBeam Companion. QR expires in five minutes."
   val checking=server!!
   task {
    AppLog.add("TV: starting loopback TLS self-test")
    try {
     withContext(Dispatchers.IO){Connection(checking.link("127.0.0.1")).probe()}
     if(server===checking) AppLog.add("TV: local TLS self-test PASSED; certificate, signing and HTTP work")
    }catch(e:Exception){
     if(e is CancellationException) throw e
     if(server===checking){
      val detail="TV local TLS self-test FAILED: "+tlsDetails(e)
      AppLog.add(detail);status=detail.take(450)
     }
    }
   }
  }catch(e:Exception){status=e.message ?: "Pairing unavailable"}
 }
 fun authorize(refresh:Boolean=false,computer:Boolean=false,edge:Boolean=false) {
  if(authorizationBusy)return
  pickerOnComputer=computer;pickerInEdge=edge
  if(computer){desktopDialog=true;pickerLink=""}
  selectionError="";selectionStep="Requesting Google Photos permission…"
  if(connection==null){selectionFailure("Pair PhotosBeam with your TV before creating a selection link.");return}
  if(refresh && session.isBlank()){selectionFailure("Select photos first.");return}
  authorizationBusy=true
  AppLog.add(if(refresh) "Phone: requesting renewed Google authorization" else "Phone: requesting Google Photos permission")
  renew=refresh
  Identity.getAuthorizationClient(this).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))).build())
   .addOnSuccessListener { r -> if(r.hasResolution()) authResult.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else authorized(r) }
   .addOnFailureListener {selectionFailure("Google authorization failed. Check Cloud configuration and retry.",it.javaClass.simpleName)}
 }
 fun authorized(r:AuthorizationResult) {
  val access=r.accessToken
  if(access.isNullOrBlank()){selectionFailure("Google returned no access token. Retry authorization.");return}
  AppLog.add("Phone: Google authorization granted");token=access
  lifecycleScope.launch {
   try{
    val c=connection ?: error("Pair with your TV first")
    if(!renew){
     selectionStep="Creating your Google Photos selection link…"
     val s=withContext(Dispatchers.IO){api(access,"sessions","POST",JSONObject().put("pickingConfig",JSONObject().put("maxItemCount","2000")))}
     session=s.getString("id");pickerLink=s.getString("pickerUri")
     AppLog.add("Phone: created multi-select Google Photos session (up to 2000 items)")
    }
    selectionStep="Connecting this selection to the TV…"
    val payload=JSONObject().put("requestId",UUID.randomUUID().toString()).put("session",session).put("token",access)
    withContext(Dispatchers.IO){c.post("session",payload)}
    stopService(NativeSharing.intent(this@MainActivity))
    status=if(pickerOnComputer) "Selection link ready. Open it on your computer using the same Google account." else "Connected. Select your photos and videos in Google Photos, then finish."
    selectionStep="Ready — copy or share the link below"
    if(!renew && !pickerOnComputer)openSelection(pickerInEdge)
   }catch(e:CancellationException){throw e}
   catch(e:Exception){selectionFailure("Could not prepare selection: "+(e.message ?: e.javaClass.simpleName),e.javaClass.simpleName)}
   finally{token="";authorizationBusy=false}
  }
 }
 private fun openSelection(edge:Boolean){
  try{
   startActivity(pickerBrowserIntent(pickerLink,edge))
   AppLog.add(if(edge)"Phone: opened Google selection in Edge; system browser defaults unchanged" else "Phone: opened Google selection with the default handler")
  }catch(e:android.content.ActivityNotFoundException){
   if(edge){edgeUnavailable=true;status="Edge is not available in this phone profile. Install Edge or use the default app."}
   else status="No app could open this selection link. Install a browser and try again."
   AppLog.add("Phone: selection browser unavailable (${if(edge)"Edge" else "default"})")
  }catch(e:SecurityException){
   if(edge)edgeUnavailable=true
   status="This phone's policy prevented opening the selection browser."
   AppLog.add("Phone: selection browser blocked by device policy")
  }
 }
 private fun installEdge(){
  try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.microsoft.emmx")))}
  catch(e:android.content.ActivityNotFoundException){
   try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id=com.microsoft.emmx")))}
   catch(e:Exception){status="Open Google Play and search for Microsoft Edge."}
  }catch(e:SecurityException){status="This phone's policy prevented opening Google Play."}
 }
 @Composable fun EdgeUnavailableDialog(){
  if(!edgeUnavailable)return
  AlertDialog(onDismissRequest={edgeUnavailable=false},title={Text("Open selection in Edge")},
   text={Column{Text("Microsoft Edge could not open the selection in this phone profile. Install or enable Edge, then try again. Your default browser stays unchanged.");TextButton(onClick={installEdge()}){Text("Get Edge")}}},
   confirmButton={TextButton(onClick={edgeUnavailable=false;openSelection(true)}){Text("Try Edge again")}},
   dismissButton={Row{TextButton(onClick={edgeUnavailable=false;openSelection(false)}){Text("Use default app")};TextButton(onClick={edgeUnavailable=false}){Text("Close")}}})
 }
 @Composable fun DesktopSelectionDialog(){
  if(!desktopDialog)return
  Dialog(onDismissRequest={if(!authorizationBusy)desktopDialog=false}){
   Surface(shape=MaterialTheme.shapes.large){Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
    Text("Choose photos on your computer",style=MaterialTheme.typography.headlineSmall)
    if(authorizationBusy){CircularProgressIndicator();Text(selectionStep)}
    else if(selectionError.isNotBlank()){
     Text(selectionError)
     Action("Try again"){authorize(computer=true)}
    }else if(pickerLink.isNotBlank()){
     Text("1. Copy or share this link to your computer.")
     OutlinedTextField(value=pickerLink,onValueChange={},readOnly=true,label={Text("Google Photos selection link")})
     Action("Copy link"){
      (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Google Photos selection link",pickerLink))
      selectionStep="Link copied. Paste it into your computer browser."
     }
     Action("Share link to computer"){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,pickerLink),"Send selection link to your computer"))}
     Text("2. Open it in a computer browser signed in to the same Google account.")
     Text("3. Search your album name, choose items using Google's picker, and finish. The TV will load them.")
     Text("PhotosBeam cannot open a browser on your computer automatically. This link expires when you finish or the session expires.")
     Text(selectionStep)
     Action("Open in Edge on this phone"){desktopDialog=false;openSelection(true)}
     Action("Open on this phone instead"){desktopDialog=false;openSelection(false)}
    }
    if(!authorizationBusy)Action("Close"){desktopDialog=false}
   }}
  }
 }

 fun load() {
  if(session.startsWith("native:")){status="Phone media: check phone remains connected, then retry playback";return}
  job?.cancel(); job=lifecycleScope.launch { try {
   status="Waiting for selection…"; AppLog.add("TV: waiting for Google Photos selection")
   withContext(Dispatchers.IO) {
    var deadline=Long.MAX_VALUE
    while(true) {
     val s=api(token,"sessions/$session"); expires=Instant.parse(s.getString("expireTime"))
     if(s.optBoolean("mediaItemsSet")) break
     val conf=s.getJSONObject("pollingConfig")
     val timeout=(conf.getString("timeoutIn").removeSuffix("s").toDouble()*1000).toLong()
     deadline=minOf(deadline,System.currentTimeMillis()+timeout,expires.toEpochMilli())
     if(System.currentTimeMillis()>=deadline) error("Selection timed out. Select again on your phone.")
     delay(maxOf(1000,(conf.getString("pollInterval").removeSuffix("s").toDouble()*1000).toLong()))
    }
   }
   while(isActive) {
    if(Instant.now()>=expires) error("Selection expired. Select again on your phone.")
    val found=withContext(Dispatchers.IO) {
     val all=mutableListOf<JSONObject>(); var page=""
     do { val r=api(token,"mediaItems?sessionId=$session&pageSize=100&pageToken=${java.net.URLEncoder.encode(page,"UTF-8")}"); val a=r.optJSONArray("mediaItems"); if(a!=null) for(i in 0 until a.length()) all+=a.getJSONObject(i); page=r.optString("nextPageToken") }while(page.isNotBlank()); all
    }
    items=found; AppLog.add("TV: loaded ${found.size} selected items"); status="${found.size} items • Photos fitted to display • Google-provided video quality"
    delay(minOf(45*60*1000L, maxOf(1000, java.time.Duration.between(Instant.now(),expires).toMillis())))
   }
  }catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "Offline. Retry."; AppLog.add("TV: selection loading stopped (${e.javaClass.simpleName})")} }
 }
 fun end(reset:Boolean=true) {
  job?.cancel(); val oldToken=token; val oldSession=session;val oldNative=items.firstOrNull()?.optJSONObject("mediaFile")?.takeIf{nativeFile(it)}
  token=""; session=""; items=emptyList(); epoch++
  if(oldToken.isNotBlank() && oldSession.isNotBlank() && !oldSession.startsWith("native:")) lifecycleScope.launch(Dispatchers.IO){runCatching{api(oldToken,"sessions/$oldSession","DELETE")}}
  if(oldNative!=null && oldToken.isNotBlank())lifecycleScope.launch(Dispatchers.IO){runCatching{
   val url=oldNative.getString("baseUrl").toHttpUrl().newBuilder().encodedPath("/stop").build()
   mediaClient(oldNative).newCall(okhttp3.Request.Builder().url(url).header("Authorization","Bearer $oldToken").post("{}".toRequestBody()).build()).execute().close()
  }}
  AppLog.add("Session ended; local state cleared; remote deletion is best effort"); status="Session ended"; if(reset) pairing()
 }
 @Composable fun Action(label:String,modifier:Modifier=Modifier,onClick:()->Unit) { if(BuildConfig.FLAVOR=="tv") androidx.tv.material3.Button(onClick=onClick,modifier=modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),colors=androidx.tv.material3.ButtonDefaults.colors(containerColor=Color.White.copy(alpha=.055f),contentColor=Color(0xFFDADADA),focusedContainerColor=Color(0xFFE0D3BD),focusedContentColor=Color.Black)){androidx.tv.material3.Text(label,style=androidx.tv.material3.MaterialTheme.typography.labelLarge,maxLines=1)} else Button(onClick=onClick,modifier=modifier){Text(label)} }

 @Composable fun ActivityLog(){
  var expanded by remember{mutableStateOf(false)}
  val entries by AppLog.entries.collectAsState()
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Action(if(expanded) "Hide activity log" else "Show activity log"){expanded=!expanded}
    if(expanded) Action("Copy log"){
     val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
     clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PhotosBeam diagnostic log",entries.joinToString("\n")))
    }
   }
   if(expanded) Text(entries.takeLast(24).asReversed().joinToString("\n").ifBlank{"No events yet"},modifier=Modifier.heightIn(max=200.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
  }
 }
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable fun Phone() {
  DesktopSelectionDialog()
  EdgeUnavailableDialog()
  var tab by rememberSaveable{mutableIntStateOf(0)}
  var manual by remember{mutableStateOf(false)}
  if(manual)ManualPairing{manual=false}
  val labels=listOf("Photos","Remote","Settings")
  val icons=listOf(AppIcons.Photos,AppIcons.Remote,AppIcons.Settings)
  BoxWithConstraints(Modifier.fillMaxSize()){
   val wide=maxWidth>=600.dp
   Scaffold(topBar={TopAppBar(title={Column{Text("PhotosBeam",style=MaterialTheme.typography.titleLarge);Text(labels[tab],style=MaterialTheme.typography.labelMedium)}})},
    bottomBar={if(!wide)NavigationBar{labels.forEachIndexed{i,label->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icons[i],null)},label={Text(label)})}}}
   ){padding->
    Row(Modifier.fillMaxSize().padding(padding)){
     if(wide)NavigationRail{labels.forEachIndexed{i,label->NavigationRailItem(selected=tab==i,onClick={tab=i},icon={Icon(icons[i],null)},label={Text(label)})}}
     Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
      ElevatedCard(Modifier.fillMaxWidth()){
       Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(if(connection!=null)"TV connected" else if(hasSavedPairing)"TV remembered" else "Connect your TV",style=MaterialTheme.typography.titleLarge)
        Text(status,style=MaterialTheme.typography.bodyMedium)
        if(connection==null){
         Button(onClick={
          reconnectJob?.cancel();nearby.stopDiscovery()
          val options=GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
          GmsBarcodeScanning.getClient(this@MainActivity,options).startScan().addOnSuccessListener{it.rawValue?.let{value->pairQr(value)}}.addOnFailureListener{status="Scan cancelled. Try the zoom camera or TV code."}
         }){Text("Scan TV QR")}
         TextButton(onClick={reconnectJob?.cancel();nearby.stopDiscovery();manual=true}){Text("Use 6-character code")}
        }
       }
      }
      when(tab){
       0->{
        if(sharedUris.isNotEmpty())OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
         Text("${sharedUris.size} items from your gallery",style=MaterialTheme.typography.titleLarge)
         Button(enabled=connection!=null && !nativeBusy && sharedUris.size<=2000,onClick={shareNative(sharedUris)}){Text("Send shared items to TV")}
         TextButton(onClick={sharedUris=emptyList()}){Text("Dismiss")}
        }}
        Text("Choose what to watch",style=MaterialTheme.typography.headlineSmall)
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
         Text("Google Photos",style=MaterialTheme.typography.titleLarge)
         Text("The TV fetches selected media directly from Google. Choose on your phone or share a selection link to your computer.")
         Button(enabled=connection!=null && !authorizationBusy,onClick={authorize()}){Text("Choose in Google Photos")}
         OutlinedButton(enabled=connection!=null && !authorizationBusy,onClick={authorize(edge=true)}){Text("Choose in Edge")}
         Text("Choose in Edge opens this selection in Microsoft Edge without changing your default browser.",style=MaterialTheme.typography.bodySmall)
         OutlinedButton(enabled=connection!=null && !authorizationBusy,onClick={authorize(computer=true)}){Text("Choose on computer")}
         if(authorizationBusy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(selectionStep)}
        }}
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
         Text("Phone & cloud picker",style=MaterialTheme.typography.titleLarge)
         Text("Use Android’s photo picker. Google Photos cloud media appears when enabled in your system picker. Your phone streams the provider-supplied files to the TV without PhotosBeam transcoding.")
         Text("Keep this phone on the same network during viewing. Cloud files may download before playback; available quality depends on the provider.",style=MaterialTheme.typography.bodySmall)
         Button(enabled=connection!=null && !nativeBusy,onClick={nativePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo).apply{if(android.os.Build.VERSION.SDK_INT>=33)setMediaCapabilitiesForTranscoding(null)}.build())}){Text(if(nativeBusy)"Preparing media…" else "Choose with Android picker")}
         OutlinedButton(enabled=connection!=null && !nativeBusy,onClick={filePicker.launch(arrayOf("image/*","video/*"))}){Text("Choose files on phone")}
         Text("For original 4K60 files, choose a local camera or downloaded original file. System picker transcoding is disabled; PhotosBeam streams the provided bytes unchanged.",style=MaterialTheme.typography.bodySmall)
         if(nativeBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
         OutlinedButton(onClick={stopService(NativeSharing.intent(this@MainActivity));status="Phone media sharing stopped"}){Text("Stop phone sharing")}
        }}
        Text("Selecting many photos",style=MaterialTheme.typography.titleLarge)
        Text("Both pickers support multiple items, subject to their limits. PhotosBeam cannot add Select all, whole-album, day or week controls inside Google’s or Android’s picker. Search an album or date in Google’s selection screen, then select the items it offers. Use any group selection controls your picker provides.")
        Text("For local media, use your gallery’s Select all or date-group controls, then Share → PhotosBeam. You can also download originals to a local folder and use Choose files on phone. Gallery sharing limits vary; Google Photos may share a web link instead of files. Google Photos selections are limited to 2,000 items per session; Android’s limit varies by device.",style=MaterialTheme.typography.bodySmall)
       }
       1->{
        Text("TV remote",style=MaterialTheme.typography.headlineSmall)
        Text(tvState.ifBlank{"Choose media, then use these controls to browse and play."})
        RemoteButtonRow(listOf("Previous" to RemoteAction.PREVIOUS,"Next" to RemoteAction.NEXT))
        RemoteButtonRow(listOf("Play" to RemoteAction.PLAY,"Pause" to RemoteAction.PAUSE,"Stop" to RemoteAction.STOP))
        PhoneViewPad(connection){status=it}
        RemoteButtonRow(listOf("Zoom −" to RemoteAction.ZOOM_OUT,"Fit" to RemoteAction.ZOOM_RESET,"Zoom +" to RemoteAction.ZOOM_IN))
        RemoteButtonRow(listOf("Pan ↑" to RemoteAction.PAN_UP))
        RemoteButtonRow(listOf("Pan ←" to RemoteAction.PAN_LEFT,"Pan ↓" to RemoteAction.PAN_DOWN,"Pan →" to RemoteAction.PAN_RIGHT))
        OutlinedButton(enabled=connection!=null,onClick={sendControl(RemoteAction.PHOTO_WARMTH)}){Text("Cycle photo warmth")}
        Text("Warmth changes photos only. Video retains its normal colors and HDR output.",style=MaterialTheme.typography.bodySmall)
        RemoteButtonRow(listOf("Start slideshow" to RemoteAction.START_SLIDESHOW,"Stop slideshow" to RemoteAction.STOP_SLIDESHOW))
       }
       2->{
        Text("Phone-to-TV media",style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){Text("Encrypt media with HTTPS");Text("Applies to the next phone selection",style=MaterialTheme.typography.bodySmall)}
         Switch(checked=encryptMedia,onCheckedChange={encryptMedia=it;getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("encrypt-media",it).apply()})
        }
        Text(if(encryptMedia)"Phone media uses encrypted HTTPS." else "Phone media uses plain HTTP on your home network. Other devices on that network may observe media and its temporary access grant. Pairing stays encrypted. Google connections always use HTTPS.",style=MaterialTheme.typography.bodySmall)
        Text("Maximum available playback quality is selected by default. 4K60 requires a supplied 4K60 file, a compatible TV decoder, sufficient network speed and a suitable display mode. PhotosBeam never transcodes phone media.")
        HorizontalDivider()
        Text("Device pairing",style=MaterialTheme.typography.titleLarge)
        Text("Pairing survives app restarts and device reboots. Open PhotosBeam on both devices to reconnect. Google permission and media selections may expire separately.")
        OutlinedButton(onClick={zoomResult.launch(Intent().setClassName(this@MainActivity,"dev.personal.photostv.ZoomScannerActivity"))}){Text("Scan with camera zoom controls")}
        OutlinedButton(enabled=hasSavedPairing,onClick={forgetPairing()}){Text("Forget TV")}
        OutlinedButton(enabled=connection!=null,onClick={authorize(true)}){Text("Renew Google authorization")}
        HorizontalDivider()
        Text("Diagnostics",style=MaterialTheme.typography.titleLarge)
        ActivityLog()
        Button(enabled=connection!=null,onClick={task{val reply=withContext(Dispatchers.IO){connection!!.post("logs",JSONObject())};tvReport=reply.getString("report");AppLog.add("Phone: TV diagnostic report fetched")}}){Text("Get TV log")}
        if(tvReport.isNotBlank()){
         Text(tvReport.takeLast(10000),Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
         OutlinedButton(onClick={startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,tvReport),"Share TV log"))}){Text("Share TV log")}
         OutlinedButton(onClick={(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PhotosBeam TV log",tvReport))}){Text("Copy TV log")}
        }
        Text("PhotosBeam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",style=MaterialTheme.typography.labelMedium)
       }
      }
     }
    }
   }
  }
 }
 @Composable private fun RemoteButtonRow(actions:List<Pair<String,RemoteAction>>){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   actions.forEach{(label,action)->FilledTonalButton(enabled=connection!=null,onClick={sendControl(action)},modifier=Modifier.weight(1f).heightIn(min=56.dp)){Text(label)}}
  }
 }
 @Composable fun ManualPairing(close:()->Unit){
  val peers=remember{mutableStateListOf<NearbyTV>()}
  var chosen by remember{mutableStateOf<NearbyTV?>(null)}
  var code by remember{mutableStateOf("")}
  var checked by remember{mutableStateOf(false)}
  var busy by remember{mutableStateOf(false)}
  var error by remember{mutableStateOf("")}
  DisposableEffect(Unit){runCatching{nearby.discover{peer->if(peers.none{it.link.host==peer.link.host && it.link.port==peer.link.port})peers.add(peer)}}.onFailure{error="Nearby TV discovery unavailable. Use QR pairing."};onDispose{nearby.stopDiscovery()}}
  Dialog(onDismissRequest={if(!busy)close()}){Surface(shape=MaterialTheme.shapes.large){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text("Pair using TV code",style=MaterialTheme.typography.headlineSmall)
   Text("On your TV choose Show 6-character code. Both devices must share a home network.")
   if(peers.isEmpty())Text("Searching for TVs… If none appear, use QR pairing; the network may block device discovery.")
   peers.forEach{peer->Button(enabled=!busy,onClick={chosen=peer;checked=false;code="";error=""}){Text(peer.name)}}
   chosen?.let{peer->
    Text("Compare this verification value with the one on the TV:")
    Text(verification(peer.link.pin),style=MaterialTheme.typography.titleMedium)
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=checked,onCheckedChange={checked=it},enabled=!busy);Text("All four groups match the TV")}
    OutlinedTextField(value=code,onValueChange={code=it.uppercase(java.util.Locale.ROOT).filter{c->c in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"}.take(6)},label={Text("6-character TV code")},singleLine=true,enabled=!busy)
    Text("Codes expire after five minutes. Five failed attempts lock manual pairing until the TV generates a new code.")
    Button(enabled=checked && code.length==6 && !busy,onClick={busy=true;error="";task{
     try{val c=Connection(peer.link);withContext(Dispatchers.IO){c.pair(code)};connected(c);status="Paired using TV code. Select photos to begin.";AppLog.add("Phone: verified manual pairing accepted");close()}
     catch(e:Exception){if(e is CancellationException)throw e;error="Pairing failed. Verify the TV code, or generate a new one. "+(if(e is java.io.IOException)tlsDetails(e) else "")}
     finally{busy=false}
    }}){Text(if(busy) "Connecting…" else "Connect")}
   }
   if(error.isNotBlank())Text(error)
   Button(enabled=!busy,onClick=close){Text("Cancel")}
  }}}
 }
 @Composable fun TV() {
  var viewport by remember{mutableStateOf(MediaViewport())}
  var zoomMode by remember{mutableStateOf(false)}
  var mediaAspect by remember{mutableFloatStateOf(0f)}
  val displayAspect=remember{val mode=(getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.mode;mode.physicalWidth.toFloat()/mode.physicalHeight}
  var tvSettings by remember{mutableStateOf(false)}
  var qualityOpen by remember{mutableStateOf(false)}
  var http1Only by remember{mutableStateOf(false)}
  var actionsFocused by remember{mutableStateOf(false)}
  var centerHeld by remember{mutableStateOf(false)}
  var fastStartup by remember{mutableStateOf(true)}
  var photoQuality by remember{mutableStateOf(PhotoQuality.SCREEN)}
  var fullQr by remember{mutableStateOf(false)}
  var showCode by remember{mutableStateOf(false)}
  var selected by remember { mutableIntStateOf(-1) }; var slideshow by remember { mutableStateOf(false) }; var loaded by remember(selected) { mutableStateOf(false) }; var retryCount by remember { mutableIntStateOf(0) }
  val idleControls=remember{IdleControls()}
  var controlsVisible by remember{mutableStateOf(true)}
  var controlActivity by remember{mutableLongStateOf(0L)}
  var restoreControlFocus by remember{mutableStateOf(false)}
  var playerState by remember(selected){mutableStateOf("")}
  val viewerFocus=remember{FocusRequester()};val firstControlFocus=remember{FocusRequester()}
  fun activity(focus:Boolean=false){val hidden=!controlsVisible;controlsVisible=true;controlActivity=idleControls.touch();if(hidden && focus)restoreControlFocus=true}
  fun openActions(){actionsFocused=true;activity();restoreControlFocus=true}
  fun transform(input:ViewportChange){
   if(items.getOrNull(selected)==null){status="Open a photo or video before zooming";return}
   slideshow=false;zoomMode=true;actionsFocused=false
   viewport=viewport.change(input,mediaAspect,displayAspect);activity()
  }
  fun resetView(){viewport=MediaViewport();zoomMode=false;activity()}
  fun backFromViewer(){
   if(actionsFocused){actionsFocused=false;controlsVisible=false;runCatching{viewerFocus.requestFocus()}}
   else if(zoomMode || viewport.scale>1f){resetView();controlsVisible=false}
   else{selected=-1;slideshow=false}
  }
  LaunchedEffect(selected,epoch){viewport=MediaViewport();zoomMode=false;mediaAspect=0f;actionsFocused=false;centerHeld=false;if(selected>=0){activity();withFrameNanos{};runCatching{viewerFocus.requestFocus()}}else qualityOpen=false}
  LaunchedEffect(selected,controlActivity,qualityOpen){
   if(selected>=0 && !qualityOpen){val expected=controlActivity;delay(idleControls.timeoutMillis);if(idleControls.shouldHide(expected,qualityOpen)){controlsVisible=false;actionsFocused=false;runCatching{viewerFocus.requestFocus()}}}
  }
  LaunchedEffect(controlsVisible,restoreControlFocus){if(controlsVisible && restoreControlFocus){withFrameNanos{};runCatching{firstControlFocus.requestFocus()};restoreControlFocus=false}}
  var qualityInfo by remember(selected){mutableStateOf("")}
  var videoOptions by remember(selected){mutableStateOf<List<VideoQualityOption>>(emptyList())}
  val context=LocalContext.current
  val loader=remember(epoch,items) { ImageLoader.Builder(context).apply{items.firstOrNull()?.optJSONObject("mediaFile")?.takeIf{nativeFile(it)}?.let{okHttpClient(mediaClient(it))}}.memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.18).build() }.diskCachePolicy(CachePolicy.DISABLED).build() }
  DisposableEffect(loader){onDispose{loader.shutdown()}}
  LaunchedEffect(items.isEmpty()){if(items.isEmpty()){selected=-1;slideshow=false}}
  fun advance(){ if(items.isNotEmpty()) selected=(selected+1)%items.size }
  SideEffect{
   remoteViewportHandler={qualityOpen=false;transform(it)}
   remoteSummary="${items.size} items • Zoom ${"%.1f".format(viewport.scale)}× • Photo warmth ${warmthLabel(photoWarmth)} • "+(if(selected<0) "Grid" else "Item ${selected+1} • $playerState")+(if(slideshow) " • Slideshow" else "")
   remoteCommandHandler={command->
    AppLog.add("TV remote command: ${command.wire}")
    if(items.isEmpty()){status="Select photos or videos before using playback controls."}
    else{
     activity();qualityOpen=false
     when(command){
      RemoteAction.NEXT->{slideshow=false;advance()}
      RemoteAction.PREVIOUS->{slideshow=false;selected=if(selected<0)items.lastIndex else (selected-1+items.size)%items.size}
      RemoteAction.PLAY->{if(selected<0)selected=0;if(items.getOrNull(selected)?.optString("type")=="VIDEO")videoControl?.invoke("play")else slideshow=true}
      RemoteAction.PAUSE->{slideshow=false;videoControl?.invoke("pause")}
      RemoteAction.STOP->{slideshow=false;selected=-1}
      RemoteAction.START_SLIDESHOW->{slideshow=true;if(selected<0)selected=0;videoControl?.invoke("play")}
      RemoteAction.STOP_SLIDESHOW->{slideshow=false}
      RemoteAction.ZOOM_IN->transform(ViewportChange(1.25f))
      RemoteAction.ZOOM_OUT->transform(ViewportChange(.8f))
      RemoteAction.ZOOM_RESET->resetView()
      RemoteAction.PAN_LEFT->transform(ViewportChange(dx=.1f))
      RemoteAction.PAN_RIGHT->transform(ViewportChange(dx=-.1f))
      RemoteAction.PAN_UP->transform(ViewportChange(dy=.1f))
      RemoteAction.PAN_DOWN->transform(ViewportChange(dy=-.1f))
      RemoteAction.PHOTO_WARMTH->cycleWarmth()
     }
    }
   }
  }
  LaunchedEffect(selected,slideshow,loaded){if(slideshow && loaded && selected>=0 && items.getOrNull(selected)?.getString("type")!="VIDEO"){delay(10_000);advance()}}
  if(fullQr && qr!=null)Dialog(onDismissRequest={fullQr=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
   Box(Modifier.fillMaxSize().background(Color.White)){
    Image(qr!!.asImageBitmap(),"Full-screen pairing QR",Modifier.fillMaxSize().padding(24.dp),contentScale=ContentScale.Fit)
    Box(Modifier.align(Alignment.TopEnd).padding(16.dp)){Action("Close QR"){fullQr=false}}
   }
  }
  if(showCode && qr!=null)Dialog(onDismissRequest={showCode=false},properties=DialogProperties(usePlatformDefaultWidth=false)){
   Surface{Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
    Text("Enter this code on your phone",style=MaterialTheme.typography.headlineMedium)
    Text(manualDisplay,style=MaterialTheme.typography.displayLarge)
    Text("Verification: $verificationDisplay",style=MaterialTheme.typography.headlineSmall)
    Text("Compare all four verification groups on your phone before connecting. Code expires after five minutes.")
    Action("Close"){showCode=false}
   }}
  }
  BackHandler(selected>=0){backFromViewer()}
  if(tvSettings)Dialog(onDismissRequest={tvSettings=false},properties=DialogProperties(usePlatformDefaultWidth=false)){
   Box(Modifier.widthIn(max=720.dp).background(Color(0xFF0D0D0F),androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(32.dp)){
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
     Text("Settings & diagnostics",style=MaterialTheme.typography.headlineMedium)
     Text("PhotosBeam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
     Text("Device pairing is remembered. Google authorization and selections expire separately. Forgetting the phone revokes its access to this TV.")
     Action("Forget paired phone"){forgetPairing();tvSettings=false}
     Action("Send detailed log to phone"){server?.sendLogToPhone();status="TV log queued for paired phone"}
     Action("Photo warmth: ${warmthLabel(photoWarmth)}"){cycleWarmth()}
     Text("Warmth uses a black-preserving photo filter. Video colors and HDR stay unchanged.")
     ActivityLog()
     Action("Close"){tvSettings=false}
    }
   }
  }
  Column(Modifier.fillMaxSize().padding(horizontal=40.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   Text("PhotosBeam",style=MaterialTheme.typography.headlineMedium)
   Text(status,style=MaterialTheme.typography.bodyLarge)

   Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
    Action("End viewing"){end()}
    Action("Retry"){retryCount++;if(session.isBlank()) pairing() else load()}
    Action("Settings & logs"){tvSettings=true}
    if(items.isNotEmpty()) Action(if(slideshow) "Pause slideshow" else "Slideshow"){slideshow=!slideshow;if(selected<0)selected=0}
   }
   qr?.let{bitmap->
    Row(horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically){
     Image(bitmap.asImageBitmap(),"Pairing QR",Modifier.size(240.dp))
     Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
      Text("Connect your phone",style=MaterialTheme.typography.headlineSmall)
      Text("Open PhotosBeam Companion on the same home network.")
      Action("Full-screen QR"){fullQr=true}
      Action("Show 6-character code"){showCode=true}
     }
    }
   }
   if(selected<0) LazyVerticalGrid(modifier=Modifier.weight(1f),columns=GridCells.Adaptive(180.dp),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
    itemsIndexed(items){i,item->androidx.tv.material3.Card(onClick={selected=i}){
     Column{
      if(item.optString("type")=="VIDEO" && nativeFile(item.getJSONObject("mediaFile")))Box(Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF101012)),contentAlignment=Alignment.Center){androidx.tv.material3.Text("▶",style=androidx.tv.material3.MaterialTheme.typography.headlineLarge)}
      else AsyncImage(ImageRequest.Builder(context).data(mediaUrl(item.getJSONObject("mediaFile"),"=w640-h360")).addHeader("Authorization","Bearer $token").build(),"${if(item.optString("type")=="VIDEO")"Video" else "Photo"} ${i+1}",imageLoader=loader,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height(110.dp))
      androidx.tv.material3.Text("${if(item.optString("type")=="VIDEO")"▶ Video" else "Photo"} ${i+1}",Modifier.padding(12.dp))
     }
    }}
   }
  }
  items.getOrNull(selected)?.let { item ->
   Dialog(onDismissRequest={backFromViewer()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
    Box(Modifier.fillMaxSize().background(Color.Black)
     .onPreviewKeyEvent{event->
      val key=event.nativeKeyEvent
      val down=key.action==android.view.KeyEvent.ACTION_DOWN
      val isVideo=item.optString("type")=="VIDEO"
      if(qualityOpen)false
      else if(actionsFocused && centerHeld && key.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER)){if(key.action==android.view.KeyEvent.ACTION_UP)centerHeld=false;true}
      else if(actionsFocused){if(down && key.keyCode!=android.view.KeyEvent.KEYCODE_BACK)activity();false}
      else when{
       down && zoomMode && key.keyCode in listOf(19,20,21,22)->{
        if(key.repeatCount%2==0)transform(when(key.keyCode){19->ViewportChange(dy=.06f);20->ViewportChange(dy=-.06f);21->ViewportChange(dx=.06f);else->ViewportChange(dx=-.06f)})
        true
       }
       down && key.keyCode in listOf(android.view.KeyEvent.KEYCODE_PLUS,android.view.KeyEvent.KEYCODE_EQUALS,android.view.KeyEvent.KEYCODE_NUMPAD_ADD)->{transform(ViewportChange(1.25f));true}
       down && key.keyCode in listOf(android.view.KeyEvent.KEYCODE_MINUS,android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT)->{transform(ViewportChange(.8f));true}
       down && key.keyCode==android.view.KeyEvent.KEYCODE_0->{resetView();true}
       down && viewerDirection(key.keyCode,isVideo)!=null->{
        activity();slideshow=false
        when(viewerDirection(key.keyCode,isVideo)){
         ViewerDirection.PREVIOUS->if(key.repeatCount==0)selected=(selected-1+items.size)%items.size
         ViewerDirection.NEXT->if(key.repeatCount==0)advance()
         ViewerDirection.SEEK_BACK->if(key.repeatCount%4==0)videoControl?.invoke("seek-back")
         ViewerDirection.SEEK_FORWARD->if(key.repeatCount%4==0)videoControl?.invoke("seek-forward")
         else->Unit
        }
        true
       }
       key.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_ENTER)->{
        if(down){if(key.repeatCount==0)centerHeld=false;if(key.isLongPress || key.repeatCount>0){centerHeld=true;openActions()}}
        else if(key.action==android.view.KeyEvent.ACTION_UP && !centerHeld){activity();if(zoomMode)transform(ViewportChange(viewport.nextZoom()/viewport.scale))else if(isVideo)videoControl?.invoke("toggle")else{openActions()}}
        true
       }
       down && key.keyCode==android.view.KeyEvent.KEYCODE_MENU->{openActions();true}
       down && key.keyCode==android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{slideshow=false;activity();videoControl?.invoke("toggle");true}
       else->false
      }
     }
     .pointerInput(Unit){awaitPointerEventScope{while(true){val event=awaitPointerEvent(PointerEventPass.Initial);if(event.type==PointerEventType.Press || event.changes.any{it.pressed})activity()}}}
     .focusRequester(viewerFocus).focusable()){
     val file=item.getJSONObject("mediaFile")
     if(item.getString("type")=="VIDEO")key(file.optString("baseUrl"),token,retryCount,fastStartup,http1Only){
      VideoViewer(file,token,Modifier.fillMaxSize(),fastStartup=fastStartup,http1Only=http1Only,viewport=viewport,zoomMode=zoomMode,onAspect={mediaAspect=it;viewport=viewport.bounded(it,displayAspect)},controlsVisible=controlsVisible,onActivity={focus->activity(focus)},onControlReady={videoControl=it},onState={playerState=it},onInfo={qualityInfo=it},onOptions={videoOptions=it},onEnded={if(slideshow)advance()},onInteraction={slideshow=false},onFailure={message->status=message})
     }else key(file.optString("baseUrl"),token,retryCount,photoQuality){
      PhotoViewer(file,token,loader,Modifier.fillMaxSize(),quality=photoQuality,viewport=viewport,warmth=photoWarmth,onAspect={mediaAspect=it;viewport=viewport.bounded(it,displayAspect)},onInfo={qualityInfo=it},onLoaded={loaded=true},onFailure={status=it})
     }
     if(controlsVisible && actionsFocused)Row(Modifier.align(Alignment.TopCenter).padding(top=16.dp).widthIn(max=850.dp).background(Color.Black.copy(alpha=.65f),androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
      if(zoomMode){
       Action("Zoom +",modifier=Modifier.focusRequester(firstControlFocus)){transform(ViewportChange(1.25f));runCatching{viewerFocus.requestFocus()}}
       Action("Zoom −"){transform(ViewportChange(.8f));runCatching{viewerFocus.requestFocus()}}
       Action("Fit"){resetView();actionsFocused=false}
      }else{
       Action("Zoom / pan",modifier=Modifier.focusRequester(firstControlFocus)){slideshow=false;zoomMode=true;actionsFocused=false;activity();runCatching{viewerFocus.requestFocus()}}
       Action(if(slideshow)"Pause slides" else "Slideshow"){resetView();slideshow=!slideshow}
      }
      Action("More"){slideshow=false;qualityOpen=true}
      Action("Grid"){selected=-1;slideshow=false}
     }
     else if(controlsVisible)androidx.tv.material3.Text(if(zoomMode)"${"%.1f".format(viewport.scale)}×   •   D-pad pan   •   OK zoom   •   Back fit" else "${selected+1} / ${items.size}   •   ${"%.1f".format(viewport.scale)}×   •   Hold OK for actions",
      Modifier.align(Alignment.TopStart).padding(20.dp).background(Color.Black.copy(alpha=.55f),androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=8.dp),
      color=Color(0xFFDADADA),style=androidx.tv.material3.MaterialTheme.typography.labelMedium)
     if(qualityOpen)Dialog(onDismissRequest={qualityOpen=false}){Surface(Modifier.padding(24.dp),shape=MaterialTheme.shapes.large){Column(Modifier.widthIn(max=650.dp).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Text("Viewing options",style=MaterialTheme.typography.headlineMedium)
      Text("Zoom ${"%.1f".format(viewport.scale)}× • Photo warmth ${warmthLabel(photoWarmth)}")
      Action("Photo warmth: ${warmthLabel(photoWarmth)}"){cycleWarmth()}
      Text("Warmth applies to photos only and keeps black pixels black. Zoom enlarges the current bounded photo decode; it does not fetch larger images.",style=MaterialTheme.typography.bodySmall)
      Action("Retry media"){retryCount++;loaded=false;qualityInfo="";videoOptions=emptyList();qualityOpen=false;load()}
      Action("Send TV log to phone"){server?.sendLogToPhone()}
      Text("Source metadata: ${sourceDimensions(file)} • ${file.optString("mimeType")}")
      Text(qualityInfo.ifBlank{"Waiting for decoded media details…"})
      if(item.getString("type")=="VIDEO"){
       if(!nativeFile(file)){
        Text("Google connection: ${if(http1Only)"HTTP/1.1 compatibility" else "automatic HTTP/2 or HTTP/1.1"}. Download redirects use a separate connection from the authorization host.")
        Action(if(http1Only)"Retry automatic Google connection" else "Retry Google using HTTP/1.1"){http1Only=!http1Only;retryCount++;qualityInfo="";qualityOpen=false;actionsFocused=false}
       }
       Text("Fast startup: ${if(fastStartup) "On" else "Off"}. Skipping an optional seek index can limit seeking on some videos.")
       Action(if(fastStartup) "Restart with seek index" else "Restart with fast startup"){fastStartup=!fastStartup;qualityInfo="";videoOptions=emptyList();qualityOpen=false}
       Text(if(nativeFile(file))"The phone streams the provider-supplied file without PhotosBeam transcoding. Cloud providers may supply a converted file. The decoded track below shows actual playback quality." else "Google supplies a transcoded video. Original-file video download and a server resolution selector are unavailable. Source metadata does not prove delivered playback quality.")
       val tracks=videoOptions.filter{it.mime.isNotBlank()}
       videoOptions.firstOrNull{it.mime.isBlank()}?.let{option->Action((if(option.selected)"✓ " else "")+option.label){option.choose();qualityOpen=false}}
       if(tracks.isNotEmpty()){
        Text("Video resolution",style=MaterialTheme.typography.titleMedium)
        tracks.distinctBy{it.width to it.height}.forEach{resolution->
         val matching=tracks.filter{it.width==resolution.width && it.height==resolution.height}
         val choice=matching.firstOrNull{it.selected} ?: matching.first()
         Action((if(matching.any{it.selected})"✓ " else "")+"${resolution.width} × ${resolution.height}"){choice.choose();qualityOpen=false}
        }
        Text("Video codec",style=MaterialTheme.typography.titleMedium)
        tracks.distinctBy{it.mime}.forEach{codec->
         val matching=tracks.filter{it.mime==codec.mime}
         val choice=matching.firstOrNull{it.selected} ?: matching.first()
         Action((if(matching.any{it.selected})"✓ " else "")+videoCodecLabel(codec.mime)){choice.choose();qualityOpen=false}
        }
        Text("Exact supplied tracks",style=MaterialTheme.typography.titleMedium)
        tracks.forEach{option->Action((if(option.selected)"✓ " else "")+option.label){option.choose();qualityOpen=false}}
        if(tracks.size==1)Text("This file contains one supported video track. Other resolutions/codecs require another source file; these controls cannot convert it.")
       }else Text("Resolution and codec choices appear after the video's metadata loads. If download headers stall, an independent HTTP/1.1 connection starts automatically.")
      }else{
       PhotoQuality.entries.forEach{quality->Action((if(photoQuality==quality) "✓ " else "")+quality.label){photoQuality=quality;loaded=false;qualityInfo="";qualityOpen=false}}
       Text("Full-size file downloads the image Google provides; display decoding remains bounded to 4K. Metadata may differ from the original upload.")
      }
      Action("Close"){qualityOpen=false}
     }}}
    }
   }
  }
 }
 override fun onDestroy(){remoteViewportHandler=null;reconnectJob?.cancel();remoteCommandHandler=null;videoControl=null;logReceiver?.cancel();nearby.close();networkWatch?.cancel();if(BuildConfig.FLAVOR=="tv")end(false);token="";server?.stop();connection=null;super.onDestroy()}
}

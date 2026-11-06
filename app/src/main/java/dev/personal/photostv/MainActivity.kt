package dev.personal.photostv

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
 private val nearby by lazy{NearbyPairing(this)}
 private var manualDisplay by mutableStateOf("")
 private var verificationDisplay by mutableStateOf("")
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
 var session=""; var expires=Instant.MAX; var server:PairServer?=null; var connection:Connection?=null
 var job:Job?=null; var renew=false
 private var networkWatch:Job?=null
 private fun localAddress()=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{it is java.net.Inet4Address && it.isSiteLocalAddress}?.hostAddress
 private val zoomResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==RESULT_OK)result.data?.getStringExtra("qr")?.let{pairQr(it)}
 }
 private fun connected(c:Connection){
  connection=c;tvReport="";logReceiver?.cancel()
  logReceiver=lifecycleScope.launch{
   var serial=0L
   while(isActive){
    delay(5000)
    try{
     val reply=withContext(Dispatchers.IO){c.post("log-inbox",JSONObject().put("after",serial))}
     serial=reply.optLong("serial",serial)
     tvState=reply.optString("state")
     val report=reply.optString("report")
     if(report.isNotBlank()){tvReport=report;AppLog.add("Phone: TV diagnostic report received")}
    }catch(e:Exception){if(e is CancellationException)throw e;delay(10000)}
   }
  }
 }
 private fun pairQr(raw:String){task{val c=Connection(Link.parse(raw));withContext(Dispatchers.IO){c.pair()};connected(c);AppLog.add("Phone: QR pairing accepted");status="Paired. Select photos to begin."}}
 private val authResult=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
  try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)) } catch(e:Exception){selectionFailure("Authorization cancelled or denied. Try again.",e.javaClass.simpleName)}
 }
 override fun onCreate(b:Bundle?) {
  super.onCreate(b); AppLog.add("Started ${BuildConfig.FLAVOR} app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  setContent { MaterialTheme(colorScheme=darkColorScheme()) { Surface(Modifier.fillMaxSize()) { if(BuildConfig.FLAVOR=="tv") TV() else Phone() } } }
  if(BuildConfig.FLAVOR=="tv") {
   pairing()
   networkWatch=lifecycleScope.launch { var previous=localAddress(); while(isActive){delay(3000);val next=localAddress();if(next!=previous){previous=next;end();status="Network changed. Scan the new pairing QR."}} }
  }
 }
 fun task(block:suspend ()->Unit) { lifecycleScope.launch { try{block()}catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "Connection failed. Retry when online."; AppLog.add("Operation failed (${e.javaClass.simpleName}); see screen for details")} } }
 fun pairing() {
  nearby.stopAdvertising();server?.stop(); server=PairServer(diagnostic={message -> runOnUiThread{status=message.take(450)}},control={action->runOnUiThread{remoteCommandHandler?.invoke(action)}},state={remoteSummary}) { j -> runOnUiThread { AppLog.add("TV: received selection authorization from companion"); val next=j.getString("session"); if(session.isNotBlank() && next!=session) end(false); token=j.getString("token"); session=next; qr=null; load() } }
  try { server!!.start(); AppLog.add("TV: secure pairing server started; QR valid for five minutes"); val host=localAddress() ?: error("Connect the TV to your home network, then retry.")
   manualDisplay=server!!.manualCode;verificationDisplay=verification(server!!.pin)
   runCatching{nearby.advertise(server!!.listeningPort,server!!.pin)}.onFailure{AppLog.add("TV: discovery unavailable; QR still works")}
   val matrix=MultiFormatWriter().encode(server!!.link(host).json(),BarcodeFormat.QR_CODE,600,600)
   qr=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply { for(x in 0..599) for(y in 0..599) setPixel(x,y,if(matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
   status="Scan using the AlbumBeam Companion. QR expires in five minutes."
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
 fun authorize(refresh:Boolean=false,computer:Boolean=false) {
  if(authorizationBusy)return
  pickerOnComputer=computer
  if(computer){desktopDialog=true;pickerLink=""}
  selectionError="";selectionStep="Requesting Google Photos permission…"
  if(connection==null){selectionFailure("Pair AlbumBeam with your TV before creating a selection link.");return}
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
    status=if(pickerOnComputer) "Selection link ready. Open it on your computer using the same Google account." else "Connected. Select your photos and videos in Google Photos, then finish."
    selectionStep="Ready — copy or share the link below"
    if(!renew && !pickerOnComputer)startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(pickerLink)))
   }catch(e:CancellationException){throw e}
   catch(e:Exception){selectionFailure("Could not prepare selection: "+(e.message ?: e.javaClass.simpleName),e.javaClass.simpleName)}
   finally{token="";authorizationBusy=false}
  }
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
     Text("AlbumBeam cannot open a browser on your computer automatically. This link expires when you finish or the session expires.")
     Text(selectionStep)
     Action("Open on this phone instead"){desktopDialog=false;startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(pickerLink)))}
    }
    if(!authorizationBusy)Action("Close"){desktopDialog=false}
   }}
  }
 }

 fun load() {
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
  job?.cancel(); val oldToken=token; val oldSession=session
  token=""; session=""; items=emptyList(); epoch++
  if(oldToken.isNotBlank() && oldSession.isNotBlank()) lifecycleScope.launch(Dispatchers.IO){runCatching{api(oldToken,"sessions/$oldSession","DELETE")}}
  AppLog.add("Session ended; local state cleared; remote deletion is best effort"); status="Session ended"; if(reset) pairing()
 }
 @Composable fun Action(label:String,modifier:Modifier=Modifier,onClick:()->Unit) { if(BuildConfig.FLAVOR=="tv") androidx.tv.material3.Button(onClick=onClick,modifier=modifier){Text(label)} else Button(onClick=onClick,modifier=modifier){Text(label)} }

 @Composable fun ActivityLog(){
  var expanded by remember{mutableStateOf(false)}
  val entries by AppLog.entries.collectAsState()
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Action(if(expanded) "Hide activity log" else "Show activity log"){expanded=!expanded}
    if(expanded) Action("Copy log"){
     val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
     clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AlbumBeam diagnostic log",entries.joinToString("\n")))
    }
   }
   if(expanded) Text(entries.takeLast(24).asReversed().joinToString("\n").ifBlank{"No events yet"},modifier=Modifier.heightIn(max=200.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
  }
 }
 @Composable fun Phone() { DesktopSelectionDialog(); Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  Text("AlbumBeam Companion v${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.headlineMedium); Text("Connect both devices to your home network. Search by album title in Google Photos, then select photos and videos.")
  Text(status)
  ActivityLog()
  if(connection!=null){
   Text("TV controls",style=MaterialTheme.typography.titleLarge)
   Text(tvState.ifBlank{"Connected to TV"})
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Action("Previous"){sendControl(RemoteAction.PREVIOUS)};Action("Next"){sendControl(RemoteAction.NEXT)}}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Action("Play"){sendControl(RemoteAction.PLAY)};Action("Pause"){sendControl(RemoteAction.PAUSE)};Action("Stop"){sendControl(RemoteAction.STOP)}}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Action("Start slideshow"){sendControl(RemoteAction.START_SLIDESHOW)};Action("Stop slideshow"){sendControl(RemoteAction.STOP_SLIDESHOW)}}
  }
  if(connection!=null)Action("Get detailed TV log"){task{val reply=withContext(Dispatchers.IO){connection!!.post("logs",JSONObject())};tvReport=reply.getString("report");AppLog.add("Phone: TV diagnostic report fetched")}}
  if(tvReport.isNotBlank()){
   Text("TV diagnostic report received")
   Text(tvReport.takeLast(10000),modifier=Modifier.heightIn(max=200.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Action("Copy TV log"){
     (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AlbumBeam TV log",tvReport))
    }
    Action("Share TV log"){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,tvReport),"Share TV log"))}
   }
  }
  Action("Scan TV QR (auto zoom)") {
   val options=GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
   GmsBarcodeScanning.getClient(this@MainActivity,options).startScan().addOnSuccessListener{it.rawValue?.let{value->pairQr(value)}}.addOnFailureListener{status="Scan cancelled or unavailable. Try the zoom camera or manual code."}
  }
  Action("Scan with zoom controls"){zoomResult.launch(Intent().setClassName(this@MainActivity,"dev.personal.photostv.ZoomScannerActivity"))}
  var manual by remember{mutableStateOf(false)}
  Action("Enter 6-character TV code"){manual=true}
  if(manual)ManualPairing{manual=false}
  Action("Select photos / replace selection") {authorize()}
  Action("Choose many on computer") {authorize(computer=true)}
  if(authorizationBusy)Text(selectionStep)
  Text("Google's picker supports multiple photos/videos, up to 2,000 per session. Search an album title to find its items. AlbumBeam cannot add a Select all or whole-album button inside Google's picker.")
  if(pickerLink.isNotBlank()){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Action("Copy selection link") {(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Google Photos selection link",pickerLink))}
    Action("Share selection link") {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,pickerLink),"Open selection on another device"))}
   }
  }
  Action("Reconnect authorization") {authorize(true)}
 } }
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
  var qualityOpen by remember{mutableStateOf(false)}
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
  LaunchedEffect(selected<0){if(selected>=0)activity()else qualityOpen=false}
  LaunchedEffect(selected,controlActivity,qualityOpen){
   if(selected>=0 && !qualityOpen){val expected=controlActivity;delay(idleControls.timeoutMillis);if(idleControls.shouldHide(expected,qualityOpen)){controlsVisible=false;if(items.getOrNull(selected)?.optString("type")!="VIDEO")runCatching{viewerFocus.requestFocus()}}}
  }
  LaunchedEffect(controlsVisible,restoreControlFocus){if(controlsVisible && restoreControlFocus){withFrameNanos{};runCatching{firstControlFocus.requestFocus()};restoreControlFocus=false}}
  var qualityInfo by remember(selected){mutableStateOf("")}
  var videoOptions by remember(selected){mutableStateOf<List<VideoQualityOption>>(emptyList())}
  val context=LocalContext.current
  val loader=remember(epoch) { ImageLoader.Builder(context).memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.18).build() }.diskCachePolicy(CachePolicy.DISABLED).build() }
  DisposableEffect(loader){onDispose{loader.shutdown()}}
  LaunchedEffect(items.isEmpty()){if(items.isEmpty()){selected=-1;slideshow=false}}
  fun advance(){ if(items.isNotEmpty()) selected=(selected+1)%items.size }
  SideEffect{
   remoteSummary="${items.size} items • "+(if(selected<0) "Grid" else "Item ${selected+1} • $playerState")+(if(slideshow) " • Slideshow" else "")
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
  BackHandler(selected>=0){selected=-1;slideshow=false}
  Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text("AlbumBeam v${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.headlineMedium)
   Action("Send detailed log to phone"){server?.sendLogToPhone();status="TV log queued. Keep the paired phone companion open to receive it."}
   if(qr!=null)Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Action("Full-screen QR"){fullQr=true};Action("Show 6-character code"){showCode=true}}
   Text(status)
   ActivityLog()
   Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    Action("End / Pair again"){end()}; Action("Retry"){retryCount++;if(session.isBlank()) pairing() else load()}
    if(items.isNotEmpty()) Action(if(slideshow) "Pause slideshow" else "Slideshow"){slideshow=!slideshow;if(selected<0)selected=0}
   }
   qr?.let{bitmap->
    Image(bitmap.asImageBitmap(),"Pairing QR",Modifier.size(300.dp))
   }
   if(selected<0) LazyVerticalGrid(columns=GridCells.Adaptive(180.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    itemsIndexed(items){i,item->Column { Action("${if(item.getString("type")=="VIDEO") "▶ " else ""}${i+1}") {selected=i}; AsyncImage(ImageRequest.Builder(context).data(item.getJSONObject("mediaFile").getString("baseUrl")+"=w640-h360").addHeader("Authorization","Bearer $token").build(),"Thumbnail",imageLoader=loader,modifier=Modifier.height(110.dp)) } }
   }
  }
  items.getOrNull(selected)?.let { item ->
   Dialog(onDismissRequest={selected=-1;slideshow=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
    Box(Modifier.fillMaxSize().background(Color.Black)
     .onPreviewKeyEvent{event->
      val key=event.nativeKeyEvent
      if(key.action==android.view.KeyEvent.ACTION_DOWN && key.keyCode!=android.view.KeyEvent.KEYCODE_BACK){
       val hidden=!controlsVisible
       val navigation=key.keyCode in listOf(19,20,21,22,23,66)
       activity(hidden && navigation)
       hidden && navigation
      }else false
     }
     .pointerInput(Unit){awaitPointerEventScope{while(true){val event=awaitPointerEvent(PointerEventPass.Initial);if(event.type==PointerEventType.Press || event.changes.any{it.pressed})activity()}}}
     .focusRequester(viewerFocus).focusable()){
     val file=item.getJSONObject("mediaFile")
     if(item.getString("type")=="VIDEO")key(file.optString("baseUrl"),token,retryCount,fastStartup){
      VideoViewer(file,token,Modifier.fillMaxSize(),fastStartup=fastStartup,controlsVisible=controlsVisible,onActivity={focus->activity(focus)},onControlReady={videoControl=it},onState={playerState=it},onInfo={qualityInfo=it},onOptions={videoOptions=it},onEnded={if(slideshow)advance()},onInteraction={slideshow=false},onFailure={message->status=message})
     }else key(file.optString("baseUrl"),token,retryCount,photoQuality){
      PhotoViewer(file,token,loader,Modifier.fillMaxSize(),quality=photoQuality,onInfo={qualityInfo=it},onLoaded={loaded=true},onFailure={status=it})
     }
     if(controlsVisible)Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.75f)).horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Action("Previous",modifier=Modifier.focusRequester(firstControlFocus)){slideshow=false;selected=(selected-1+items.size)%items.size}
      Action("Next"){slideshow=false;advance()}
      Action(if(slideshow) "Pause slideshow" else "Slideshow"){slideshow=!slideshow}
      Action("Retry"){retryCount++;loaded=false;qualityInfo="";videoOptions=emptyList();load()}
      Action("Quality & info"){slideshow=false;qualityOpen=true}
      Action("TV log → phone"){server?.sendLogToPhone()}
      Action("Grid"){selected=-1;slideshow=false}
     }
     if(qualityOpen)Dialog(onDismissRequest={qualityOpen=false}){Surface(Modifier.padding(24.dp),shape=MaterialTheme.shapes.large){Column(Modifier.widthIn(max=650.dp).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Text("Quality & info",style=MaterialTheme.typography.headlineMedium)
      Text("Source metadata: ${sourceDimensions(file)} • ${file.optString("mimeType")}")
      Text(qualityInfo.ifBlank{"Waiting for decoded media details…"})
      if(item.getString("type")=="VIDEO"){
       Text("Fast startup: ${if(fastStartup) "On" else "Off"}. Skipping an optional seek index can limit seeking on some videos.")
       Action(if(fastStartup) "Restart with seek index" else "Restart with fast startup"){fastStartup=!fastStartup;qualityInfo="";videoOptions=emptyList();qualityOpen=false}
       Text("Google supplies a transcoded video. Original-file video download and a server resolution selector are unavailable. Source metadata does not prove delivered playback quality.")
       if(videoOptions.size>1){Text("Available supported video tracks:");videoOptions.forEach{option->Action((if(option.selected) "✓ " else "")+option.label){option.choose();qualityOpen=false}}}
       else if(videoOptions.size==1)Text("One supported rendition: ${videoOptions.single().label}. No alternate stream quality is available.")
       else Text("No playable video track has been reported yet. Check the TV log if playback fails.")
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
 override fun onDestroy(){remoteCommandHandler=null;videoControl=null;logReceiver?.cancel();nearby.close();networkWatch?.cancel();if(BuildConfig.FLAVOR=="tv")end(false);token="";server?.stop();connection=null;super.onDestroy()}
}

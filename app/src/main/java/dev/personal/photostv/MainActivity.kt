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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 var session=""; var expires=Instant.MAX; var server:PairServer?=null; var connection:Connection?=null
 var job:Job?=null; var renew=false
 private var networkWatch:Job?=null
 private fun localAddress()=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{it is java.net.Inet4Address && it.isSiteLocalAddress}?.hostAddress
 private val zoomResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==RESULT_OK)result.data?.getStringExtra("qr")?.let{pairQr(it)}
 }
 private fun pairQr(raw:String){task{val c=Connection(Link.parse(raw));withContext(Dispatchers.IO){c.pair()};connection=c;AppLog.add("Phone: QR pairing accepted");status="Paired. Select photos to begin."}}
 private val authResult=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
  try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)) } catch(e:Exception){status="Authorization cancelled or denied. Try again."; AppLog.add("Phone: authorization cancelled or denied (${e.javaClass.simpleName})")}
 }
 override fun onCreate(b:Bundle?) {
  super.onCreate(b); AppLog.add("Started ${BuildConfig.FLAVOR} app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}"); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  setContent { MaterialTheme(colorScheme=darkColorScheme()) { Surface(Modifier.fillMaxSize()) { if(BuildConfig.FLAVOR=="tv") TV() else Phone() } } }
  if(BuildConfig.FLAVOR=="tv") {
   pairing()
   networkWatch=lifecycleScope.launch { var previous=localAddress(); while(isActive){delay(3000);val next=localAddress();if(next!=previous){previous=next;end();status="Network changed. Scan the new pairing QR."}} }
  }
 }
 fun task(block:suspend ()->Unit) { lifecycleScope.launch { try{block()}catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "Connection failed. Retry when online."; AppLog.add("Operation failed (${e.javaClass.simpleName}); see screen for details")} } }
 fun pairing() {
  nearby.stopAdvertising();server?.stop(); server=PairServer(diagnostic={message -> runOnUiThread{status=message.take(450)}}) { j -> runOnUiThread { AppLog.add("TV: received selection authorization from companion"); val next=j.getString("session"); if(session.isNotBlank() && next!=session) end(false); token=j.getString("token"); session=next; qr=null; load() } }
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
 fun authorize(refresh:Boolean=false) {
  if(connection==null){status="Pair with your TV first.";return}
  if(refresh && session.isBlank()){status="Select photos first.";return}
  AppLog.add(if(refresh) "Phone: requesting renewed Google authorization" else "Phone: requesting Google Photos permission")
  renew=refresh
  Identity.getAuthorizationClient(this).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))).build())
   .addOnSuccessListener { r -> if(r.hasResolution()) authResult.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else authorized(r) }
   .addOnFailureListener {status="Google authorization failed. Check Cloud configuration and retry."; AppLog.add("Phone: Google authorization failed (${it.javaClass.simpleName})")}
 }
 fun authorized(r:AuthorizationResult) { AppLog.add("Phone: Google authorization granted"); token=r.accessToken ?: return; task {
  val c=connection ?: error("Pair with your TV first")
  if(!renew) { val s=withContext(Dispatchers.IO){api(token,"sessions","POST")}; session=s.getString("id"); AppLog.add("Phone: opening Google Photos picker"); startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(s.getString("pickerUri")))) }
  val payload=JSONObject().put("requestId",UUID.randomUUID().toString()).put("session",session).put("token",token)
  withContext(Dispatchers.IO){c.post("session",payload)}
  token=""; status="Connected. Finish selecting in Google Photos, then use your TV remote."
 } }
 fun load() {
  job?.cancel(); job=lifecycleScope.launch { try {
   status="Waiting for selection…"; AppLog.add("TV: waiting for Google Photos selection")
   withContext(Dispatchers.IO) {
    var deadline=System.currentTimeMillis()+600_000
    while(true) {
     val s=api(token,"sessions/$session"); expires=Instant.parse(s.getString("expireTime"))
     if(s.optBoolean("mediaItemsSet")) break
     val conf=s.getJSONObject("pollingConfig")
     val timeout=(conf.getString("timeoutIn").removeSuffix("s").toDouble()*1000).toLong()
     deadline=minOf(deadline,System.currentTimeMillis()+timeout)
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
 @Composable fun Action(label:String,onClick:()->Unit) { if(BuildConfig.FLAVOR=="tv") androidx.tv.material3.Button(onClick=onClick){Text(label)} else Button(onClick=onClick){Text(label)} }

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
 @Composable fun Phone() { Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  Text("AlbumBeam Companion v${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.headlineMedium); Text("Connect both devices to your home network. Search by album title in Google Photos, then select photos and videos.")
  Text(status)
  ActivityLog()
  Action("Scan TV QR (auto zoom)") {
   val options=GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
   GmsBarcodeScanning.getClient(this@MainActivity,options).startScan().addOnSuccessListener{it.rawValue?.let{value->pairQr(value)}}.addOnFailureListener{status="Scan cancelled or unavailable. Try the zoom camera or manual code."}
  }
  Action("Scan with zoom controls"){zoomResult.launch(Intent().setClassName(this@MainActivity,"dev.personal.photostv.ZoomScannerActivity"))}
  var manual by remember{mutableStateOf(false)}
  Action("Enter 6-character TV code"){manual=true}
  if(manual)ManualPairing{manual=false}
  Action("Select photos / replace selection") {authorize()}
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
     try{val c=Connection(peer.link);withContext(Dispatchers.IO){c.pair(code)};connection=c;status="Paired using TV code. Select photos to begin.";AppLog.add("Phone: verified manual pairing accepted");close()}
     catch(e:Exception){if(e is CancellationException)throw e;error="Pairing failed. Verify the TV code, or generate a new one. "+(if(e is java.io.IOException)tlsDetails(e) else "")}
     finally{busy=false}
    }}){Text(if(busy) "Connecting…" else "Connect")}
   }
   if(error.isNotBlank())Text(error)
   Button(enabled=!busy,onClick=close){Text("Cancel")}
  }}}
 }
 @Composable fun TV() {
  var fullQr by remember{mutableStateOf(false)}
  var showCode by remember{mutableStateOf(false)}
  var selected by remember { mutableIntStateOf(-1) }; var slideshow by remember { mutableStateOf(false) }; var loaded by remember(selected) { mutableStateOf(false) }; var retryCount by remember { mutableIntStateOf(0) }
  val context=LocalContext.current
  val loader=remember(epoch) { ImageLoader.Builder(context).memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.18).build() }.diskCachePolicy(CachePolicy.DISABLED).build() }
  DisposableEffect(loader){onDispose{loader.shutdown()}}
  LaunchedEffect(items.isEmpty()){if(items.isEmpty()){selected=-1;slideshow=false}}
  fun advance(){ if(items.isNotEmpty()) selected=(selected+1)%items.size }
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
    itemsIndexed(items){i,item->Column { Action("${if(item.getString("type")=="VIDEO") "▶ " else ""}${i+1}") {selected=i}; AsyncImage(ImageRequest.Builder(context).data(item.getJSONObject("mediaFile").getString("baseUrl")+"=w320-h180").addHeader("Authorization","Bearer $token").build(),"Thumbnail",imageLoader=loader,modifier=Modifier.height(110.dp)) } }
   } else items.getOrNull(selected)?.let { item ->
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Action("Previous"){slideshow=false;selected=(selected-1+items.size)%items.size};Action("Next / Skip"){slideshow=false;advance()};Action("Grid"){selected=-1;slideshow=false}}
    val file=item.getJSONObject("mediaFile"); val base=file.getString("baseUrl")
    if(item.getString("type")=="VIDEO") key(base,token,retryCount) {
     val slideNow by rememberUpdatedState(slideshow)
     val player=remember { AppLog.add("TV: preparing video playback"); ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token")))).build().apply{setMediaItem(MediaItem.fromUri(base+"=dv"));prepare();playWhenReady=true} }
     DisposableEffect(player){val listener=object:Player.Listener {override fun onPlayWhenReadyChanged(ready:Boolean,reason:Int){if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)slideshow=false}; override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)slideshow=false}; override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED && slideNow)advance()};override fun onPlayerError(error:PlaybackException){status="Video unavailable or unsupported. Retry or skip."; AppLog.add("Video error: ${error.errorCodeName}")}};player.addListener(listener);onDispose{player.release()}}
     AndroidView(factory={PlayerView(it).apply{this.player=player;useController=true;requestFocus()}},modifier=Modifier.fillMaxSize())
    } else {
     val d=windowManager.defaultDisplay.mode
     key(base,retryCount) { AsyncImage(ImageRequest.Builder(context).data(base+"=w${d.physicalWidth}-h${d.physicalHeight}").addHeader("Authorization","Bearer $token").memoryCacheKey(base+token.hashCode()).build(),"Photo",imageLoader=loader,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize(),onSuccess={loaded=true},onError={status="Photo failed to load. Retry or skip."; AppLog.add("Photo loading failed: ${it.result.throwable.javaClass.simpleName}")}) }
    }
   }
  }
 }
 override fun onDestroy(){nearby.close();networkWatch?.cancel();if(BuildConfig.FLAVOR=="tv")end(false);token="";server?.stop();connection=null;super.onDestroy()}
}

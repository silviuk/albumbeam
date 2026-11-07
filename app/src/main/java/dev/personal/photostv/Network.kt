package dev.personal.photostv

import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.tls.HeldCertificate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

val googleHttp=OkHttpClient.Builder().followRedirects(false).build()
fun api(token:String,path:String,method:String="GET",body:JSONObject?=null):JSONObject {
 val b=Request.Builder().url("https://photospicker.googleapis.com/v1/"+path).header("Authorization","Bearer $token")
 AppLog.add("Google $method ${path.substringBefore("/").substringBefore("?")}")
 if(method!="GET") b.method(method,if(method=="POST") (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()) else null)
 googleHttp.newCall(b.build()).execute().use { r -> AppLog.add("Google response HTTP ${r.code}"); if(!r.isSuccessful) error("Google ${r.code}: "+when(r.code){401->"Reconnect phone";403->"Access denied; reconnect or select again";404->"Selection expired; select again";else->"Retry when online"}); return JSONObject(r.body?.string().orEmpty().ifBlank{"{}"}) }
}
class PairServer(private val diagnostic:(String)->Unit = {},private val control:(RemoteAction)->Unit = {},private val state:()->String = {""}, private val nativeSharing:()->Boolean={false}, private val selectionId:()->String={""}, val device:DeviceIdentity=DeviceIdentity.create(), private val initiallyPaired:Boolean=false, private val onPaired:()->Unit={}, val receive:(JSONObject)->Unit):NanoHTTPD(0) {
 private val manualGate=ManualGate()
 val manualCode get()=manualGate.code
 private var paired=initiallyPaired
 private val commandIds=linkedSetOf<String>()
 private var reportSerial=0L
 private var pendingReport=""
 @Synchronized fun sendLogToPhone(){pendingReport=AppLog.export();reportSerial++;AppLog.add("TV: diagnostic report queued for paired phone")}
 private val gate=PairGate(); private val auth=device.auth; private val seen=mutableSetOf<String>()
 val pin:String
 private val logger=java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name)
 @Volatile private var stopping=false
 private var previousLevel:java.util.logging.Level?=null
 private val diagnostics=object:java.util.logging.Handler(){
  override fun publish(record:java.util.logging.LogRecord){
   if(stopping)return
   val failure=record.thrown ?: return
   // TLS details contain no HTTP payload, pairing secret or Google bearer token.
   val isTls=generateSequence(failure){it.cause}.take(8).any{it is javax.net.ssl.SSLException || it is java.security.GeneralSecurityException}
   val details=if(isTls || failure is java.net.SocketException || failure is java.net.SocketTimeoutException) tlsDetails(failure) else failure.javaClass.simpleName
   AppLog.add("TV socket error: "+details)
   diagnostic("TV socket error: "+details)
  }
  override fun flush(){}
  override fun close(){}
 }
 override fun stop(){stopping=true;super.stop();logger.removeHandler(diagnostics);logger.level=previousLevel}
 init {
  val held=device.held
  pin=fingerprint(held.certificate.encoded)
  AppLog.add("TV: encrypted software EC device identity loaded")
  val identity=PairingKeyManager("albumbeam-session",held.keyPair.private,arrayOf(held.certificate)){chosen ->
   AppLog.add(if(chosen) "TV: pairing signing identity selected" else "TV: trying another TLS key type")
  }
  val ssl=SSLContext.getInstance("TLS").apply{init(arrayOf(identity),null,SecureRandom())}
  // Keep the supported pairing protocol explicit; do not enable obsolete TLS.
  // This affects only LAN pairing; Google requests retain normal TLS negotiation.
  makeSecure(ssl.serverSocketFactory,arrayOf("TLSv1.2"))
  previousLevel=logger.level
  logger.level=java.util.logging.Level.FINE
  logger.addHandler(diagnostics)
 }
 fun link(host:String)=Link(host,listeningPort,pin,gate.code)
 @Synchronized override fun serve(s:IHTTPSession):Response = try {
  if(s.uri!="/log-inbox")AppLog.add("TV request: "+when(s.uri){"/health"->"local TLS self-test";"/pair"->"pair";"/manual-pair"->"manual pair";"/control"->"remote control";"/logs"->"diagnostic report";"/session"->"session";else->"unknown route"})
  require(s.method==Method.POST)
  require((s.headers["content-length"]?.toLongOrNull() ?: 0) in 1..2097152)
  val data=mutableMapOf<String,String>(); s.parseBody(data); val j=JSONObject(data["postData"] ?: "{}")
  if(s.uri=="/health") newFixedLengthResponse("{\"ready\":true}")
  else if(s.uri=="/pair" || s.uri=="/manual-pair") {
   val accepted=!paired && if(s.uri=="/pair")gate.consume(j.optString("code")) else manualGate.consume(j.optString("code"))
   if(!accepted)newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Pairing expired, locked or rejected")
   else {paired=true;onPaired();newFixedLengthResponse(JSONObject().put("auth",auth).toString())}
  }
  else if(s.headers["authorization"]!="Bearer $auth") newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Rejected")
  else if(s.uri=="/control"){
   val action=requireNotNull(RemoteAction.parse(j.optString("action")))
   val id=j.getString("requestId");require(id.isNotBlank() && id.length<=128)
   if(commandIds.add(id)){control(action);if(commandIds.size>256)commandIds.remove(commandIds.first())}
   newFixedLengthResponse(JSONObject().put("accepted",true).toString())
  }
  else if(s.uri=="/logs")newFixedLengthResponse(JSONObject().put("report",AppLog.export()).toString())
  else if(s.uri=="/log-inbox")newFixedLengthResponse(JSONObject().put("serial",reportSerial).put("state",state()).put("session",selectionId()).put("nativeSharing",nativeSharing()).put("report",if(j.optLong("after",0)<reportSerial)pendingReport else "").toString())
  else { require(s.uri=="/session");validateSelection(j);val id=j.getString("requestId"); if(id !in seen) { receive(j); seen.add(id);if(seen.size>256)seen.remove(seen.first()) }; newFixedLengthResponse("{}") }
 } catch(e:Exception) { newFixedLengthResponse(Response.Status.BAD_REQUEST,"text/plain","Invalid request") }
}
class Connection(val link:Link) {
 var auth=""
 private val trust=object:X509TrustManager {
  override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
  override fun checkClientTrusted(c:Array<X509Certificate>,a:String)=throw java.security.cert.CertificateException()
  override fun checkServerTrusted(c:Array<X509Certificate>,a:String) { if(c.isEmpty() || fingerprint(c[0].encoded)!=link.pin) throw java.security.cert.CertificateException("TV certificate mismatch") }
 }
 private val ssl=SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),SecureRandom())}
 val client=OkHttpClient.Builder().eventListener(object:EventListener(){
  override fun secureConnectStart(call:Call){AppLog.add("TLS client: starting handshake")}
  override fun secureConnectEnd(call:Call,handshake:Handshake?){AppLog.add("TLS client: established (${handshake?.tlsVersion?.javaName}, ${handshake?.cipherSuite?.javaName})")}
  override fun callFailed(call:Call,ioe:java.io.IOException){AppLog.add("TLS client: request failed: "+tlsDetails(ioe))}
 }).sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier{host,_->host==link.host}.connectTimeout(4,java.util.concurrent.TimeUnit.SECONDS).readTimeout(10,java.util.concurrent.TimeUnit.SECONDS).callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
 fun post(path:String,j:JSONObject):JSONObject = client.newCall(Request.Builder().url("https://${link.host}:${link.port}/$path").header("Authorization","Bearer $auth").post(j.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { require(it.isSuccessful){"TV connection rejected. Pair again."}; JSONObject(it.body!!.string()) }
 fun probe(){require(post("health",JSONObject()).getBoolean("ready"))}
 fun pair(manualCode:String?=null){
  AppLog.add(if(manualCode==null) "Phone: QR pairing request started" else "Phone: manual pairing request started")
  try { auth=post(if(manualCode==null) "pair" else "manual-pair",JSONObject().put("code",manualCode ?: link.code)).getString("auth"); AppLog.add("Phone: pairing accepted") }
  catch(e:java.io.IOException){
   val detail=when(e){
    is javax.net.ssl.SSLException -> "Secure pairing failed. Update AlbumBeam on the TV, choose End / Pair again, and rescan."
    is java.net.SocketTimeoutException -> "TV did not respond. Check both devices are on the same home network."
    is java.net.ConnectException -> "Cannot reach TV. Keep AlbumBeam open on the TV and scan a fresh QR."
    else -> "TV connection closed. Update the TV app and scan a fresh QR."
   }
   throw java.io.IOException(detail+"\nPhone TLS details: "+tlsDetails(e),e)
  }
 }
}

/** Used only with local pairing errors, never with Google API responses. */
fun tlsDetails(error:Throwable):String = generateSequence(error){it.cause}.take(8).toList().asReversed()
 .map{it.javaClass.simpleName+": "+(it.message ?: "No detail")}.joinToString(" → ").take(1400)

/** Validate before dispatching onto the UI thread; malformed LAN requests cannot crash the activity. */
fun validateSelection(payload:JSONObject){
 require(payload.getString("requestId").length in 1..128)
 require(payload.getString("token").isNotBlank())
 val id=payload.getString("session");require(id.isNotBlank())
 if(payload.optString("source")=="native"){
  require(id.startsWith("native:"))
  val items=payload.getJSONArray("items");require(items.length() in 1..2000)
  var origin=""
  for(i in 0 until items.length()){
   val item=items.getJSONObject(i);require(item.getString("type") in listOf("VIDEO","PHOTO"))
   val file=item.getJSONObject("mediaFile");require(file.getString("nativePin").matches(Regex("[a-f0-9]{64}")))
   val uri=java.net.URI(file.getString("baseUrl"))
   require(uri.scheme in listOf("https","http") && privateLanHost(uri.host.orEmpty()) && uri.port in 1..65535 && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null)
   require(uri.path.matches(Regex("/media/[0-9]+")))
   val next="${uri.scheme}://${uri.host}:${uri.port}/${file.getString("nativePin")}"
   if(i==0)origin=next else require(next==origin)
  }
 }else require(!id.startsWith("native:"))
}

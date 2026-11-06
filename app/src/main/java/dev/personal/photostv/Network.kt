package dev.personal.photostv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

val googleHttp=OkHttpClient.Builder().followRedirects(false).build()
fun api(token:String,path:String,method:String="GET"):JSONObject {
 val b=Request.Builder().url("https://photospicker.googleapis.com/v1/"+path).header("Authorization","Bearer $token")
 AppLog.add("Google $method ${path.substringBefore("/").substringBefore("?")}")
 if(method!="GET") b.method(method,if(method=="POST") "{}".toRequestBody("application/json".toMediaType()) else null)
 googleHttp.newCall(b.build()).execute().use { r -> AppLog.add("Google response HTTP ${r.code}"); if(!r.isSuccessful) error("Google ${r.code}: "+when(r.code){401->"Reconnect phone";403->"Access denied; reconnect or select again";404->"Selection expired; select again";else->"Retry when online"}); return JSONObject(r.body?.string().orEmpty().ifBlank{"{}"}) }
}
class PairServer(private val diagnostic:(String)->Unit = {}, val receive:(JSONObject)->Unit):NanoHTTPD(0) {
 private val gate=PairGate(); private val auth=secret(); private val seen=mutableSetOf<String>()
 val pin:String
 private val logger=java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name)
 private var previousLevel:java.util.logging.Level?=null
 private val diagnostics=object:java.util.logging.Handler(){
  override fun publish(record:java.util.logging.LogRecord){
   val failure=record.thrown ?: return
   // TLS details contain no HTTP payload, pairing secret or Google bearer token.
   if(generateSequence(failure){it.cause}.take(8).any{it is javax.net.ssl.SSLException || it is java.security.GeneralSecurityException})
    { AppLog.add("TV TLS error: "+tlsDetails(failure)); diagnostic("TV TLS error: "+tlsDetails(failure)) }
  }
  override fun flush(){}
  override fun close(){}
 }
 override fun stop(){super.stop();logger.removeHandler(diagnostics);logger.level=previousLevel}
 init {
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  // v0.1's key authorized only SHA-256, but Android TLS signs prehashed data.
  // Key authorizations cannot be edited, so migrate to a fresh local TLS key.
  val alias="photos-tv-pair-v2"
  if(!ks.containsAlias(alias)) KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply { initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS).build()) }.generateKeyPair()
  // Avoid the key manager selecting the legacy, incompatible RSA key.
  if(ks.containsAlias("photos-tv-pair")) ks.deleteEntry("photos-tv-pair")
  AppLog.add("TV pairing certificate ready (v2 key)")
  pin=fingerprint(ks.getCertificate(alias).encoded)
  val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,null)}
  val ssl=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,SecureRandom())}
  // Explicitly enable TLS 1.2 for compatibility with older TV keystores.
  // This affects only LAN pairing; Google requests retain normal TLS negotiation.
  makeSecure(ssl.serverSocketFactory,arrayOf("TLSv1.2"))
  previousLevel=logger.level
  logger.level=java.util.logging.Level.FINE
  logger.addHandler(diagnostics)
 }
 fun link(host:String)=Link(host,listeningPort,pin,gate.code)
 @Synchronized override fun serve(s:IHTTPSession):Response = try {
  AppLog.add("TV request: "+when(s.uri){"/pair"->"pair";"/session"->"session";else->"unknown route"})
  require(s.method==Method.POST)
  require((s.headers["content-length"]?.toLongOrNull() ?: 0) in 1..16384)
  val data=mutableMapOf<String,String>(); s.parseBody(data); val j=JSONObject(data["postData"] ?: "{}")
  if(s.uri=="/pair") { if(!gate.consume(j.optString("code"))) newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Pairing expired or rejected") else newFixedLengthResponse(JSONObject().put("auth",auth).toString()) }
  else if(s.headers["authorization"]!="Bearer $auth") newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Rejected")
  else { require(s.uri=="/session"); val id=j.getString("requestId"); if(id !in seen) { receive(j); seen.add(id) }; newFixedLengthResponse("{}") }
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
 private val client=OkHttpClient.Builder().eventListener(object:EventListener(){
  override fun secureConnectStart(call:Call){AppLog.add("Phone: starting TLS handshake")}
  override fun secureConnectEnd(call:Call,handshake:Handshake?){AppLog.add("Phone: TLS established (${handshake?.tlsVersion?.javaName}, ${handshake?.cipherSuite?.javaName})")}
  override fun callFailed(call:Call,ioe:java.io.IOException){AppLog.add("Phone: local request failed: "+tlsDetails(ioe))}
 }).sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier{host,_->host==link.host}.followRedirects(false).build()
 fun post(path:String,j:JSONObject):JSONObject = client.newCall(Request.Builder().url("https://${link.host}:${link.port}/$path").header("Authorization","Bearer $auth").post(j.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { require(it.isSuccessful){"TV connection rejected. Pair again."}; JSONObject(it.body!!.string()) }
 fun pair(){
  AppLog.add("Phone: pairing request started")
  try { auth=post("pair",JSONObject().put("code",link.code)).getString("auth"); AppLog.add("Phone: pairing accepted") }
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

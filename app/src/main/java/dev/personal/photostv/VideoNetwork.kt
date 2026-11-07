package dev.personal.photostv

import okhttp3.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Avoid long serial IPv6 black-hole attempts on TV networks; retain IPv6 as fallback. */
fun ipv4First(addresses:List<InetAddress>):List<InetAddress> = addresses.sortedBy{if(it is Inet4Address)0 else 1}

fun videoHttpClient(http1Only:Boolean=false):OkHttpClient = OkHttpClient.Builder()
 .protocols(if(http1Only)listOf(Protocol.HTTP_1_1)else listOf(Protocol.HTTP_2,Protocol.HTTP_1_1))
 .retryOnConnectionFailure(false)
 .followRedirects(true).followSslRedirects(false)
 .connectTimeout(6,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
 .dns(object:Dns{override fun lookup(hostname:String)=ipv4First(Dns.SYSTEM.lookup(hostname))})
 .eventListenerFactory{
  object:EventListener(){
   private val started=System.nanoTime()
   private fun event(text:String){AppLog.add("Video network +${(System.nanoTime()-started)/1_000_000}ms: $text")}
   override fun dnsStart(call:Call,domainName:String){event("DNS start $domainName")}
   override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>){event("DNS complete $domainName; IPv4=${inetAddressList.count{it is Inet4Address}}, IPv6=${inetAddressList.count{it !is Inet4Address}}")}
   override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy){event("connect ${if(inetSocketAddress.address is Inet4Address) "IPv4" else "IPv6"} port ${inetSocketAddress.port}")}
   override fun secureConnectStart(call:Call){event("TLS handshake start")}
   override fun secureConnectEnd(call:Call,handshake:Handshake?){event("TLS ready ${handshake?.tlsVersion?.javaName}")}
   override fun connectFailed(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?,ioe:java.io.IOException){event("connect failed ${mediaError(ioe)}")}
   override fun connectionAcquired(call:Call,connection:okhttp3.Connection){event("connection acquired; route-host=${connection.route().address.url.host}; protocol=${connection.protocol()}")}
   override fun requestHeadersEnd(call:Call,request:Request){event("request headers sent to ${request.url.host}; Range=${request.header("Range") ?: "none"}")}
   override fun responseHeadersEnd(call:Call,response:Response){event("response ${response.code} from ${response.request.url.host}")}
   override fun callFailed(call:Call,ioe:java.io.IOException){event("request failed ${mediaError(ioe)}")}
  }
 }
 .addNetworkInterceptor{chain->
  // Avoid gzip/chunk transformations obscuring byte ranges in MP4 delivery.
  val response=chain.proceed(chain.request().newBuilder().header("Accept-Encoding","identity").build())
  AppLog.add("Video HTTP ${response.code}; protocol=${response.protocol}; content-type=${response.header("Content-Type")}; length=${response.header("Content-Length")}; requested-range=${chain.request().header("Range") ?: "none"}; content-range=${response.header("Content-Range") ?: "none"}; accept-ranges=${response.header("Accept-Ranges") ?: "unspecified"}; TTFB=${response.receivedResponseAtMillis-response.sentRequestAtMillis}ms")
  val range=chain.request().header("Range")
  if(response.code==200 && range!=null && !range.startsWith("bytes=0-"))AppLog.add("Video: server ignored a nonzero byte range; reading/skipping from the start can delay MP4 metadata and seeking")
  response
 }.build()

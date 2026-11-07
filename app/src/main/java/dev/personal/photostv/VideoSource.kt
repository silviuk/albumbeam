package dev.personal.photostv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Signed download URLs and bearer tokens stay in memory and never appear in diagnostics. */
class VideoLocation(val url:HttpUrl,val headers:Map<String,String>){
 override fun toString()="VideoLocation(origin=${url.scheme}://${url.host}:${url.port})"
}
internal fun sameOrigin(a:HttpUrl,b:HttpUrl)=a.scheme==b.scheme && a.host==b.host && a.port==b.port
private suspend fun Call.headersResponse():Response=suspendCancellableCoroutine{continuation->
 continuation.invokeOnCancellation{cancel()}
 enqueue(object:Callback{
  override fun onFailure(call:Call,e:IOException){if(!continuation.isCancelled)continuation.resumeWithException(e)}
  override fun onResponse(call:Call,response:Response){continuation.resume(response){_,value,_->value.close()}}
 })
}

/** Resolve only the authorization hop. The player opens the returned origin with its own pool,
 * preventing HTTP/2 coalescing from reusing lh3's connection for Google's download frontend.
 * Do not fetch the video body here or turn a header timeout into a whole-file download. */
suspend fun locateVideoSource(base:String,token:String,client:OkHttpClient):VideoLocation {
 val original=base.toHttpUrl()
 require(original.isHttps){"Google video requires HTTPS"}
 val authorization=mapOf("Authorization" to "Bearer $token")
 val request=Request.Builder().url(original).header("Authorization","Bearer $token")
  .header("Range","bytes=0-0").header("Accept-Encoding","identity").build()
 AppLog.add("Video: resolving Google download origin (separate connection pool)")
 client.newCall(request).headersResponse().use{response->
  if(response.code in listOf(301,302,303,307,308)){
   val destination=response.header("Location")?.let{original.resolve(it)} ?: throw IOException("Google returned an invalid video redirect")
   require(destination.isHttps && destination.username.isEmpty() && destination.password.isEmpty()){"Unsafe video redirect rejected"}
   AppLog.add("Video: resolved download host ${destination.host}; new origin connection; authorization ${if(sameOrigin(original,destination))"retained" else "removed"}")
   return VideoLocation(destination,if(sameOrigin(original,destination))authorization else emptyMap())
  }
  if(!response.isSuccessful)throw IOException("Google video lookup HTTP ${response.code}")
  AppLog.add("Video: media is served on the original host; separate player connection")
  return VideoLocation(original,authorization)
 }
}

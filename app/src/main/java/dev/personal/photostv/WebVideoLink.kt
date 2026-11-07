package dev.personal.photostv

import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/** A browser-supplied progressive video URL. Never imports cookies or Google OAuth tokens. */
data class WebVideoLink(val url:String,val mime:String="video/mp4",val width:Int=0,val height:Int=0){
 fun payload():JSONObject {
  val file=JSONObject().put("baseUrl",url).put("webStream",true).put("mimeType",mime)
   .put("mediaFileMetadata",JSONObject().put("width",width.toString()).put("height",height.toString()))
  return JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("session","web:"+secret()).put("token","web-source")
   .put("source","web").put("items",JSONArray().put(JSONObject().put("id","browser-video").put("type","VIDEO").put("mediaFile",file)))
 }
 companion object {
  fun validateUrl(value:String):String {
   require(value.length in 1..16000){"Playback URL is missing or too long"}
   val uri=URI(value);val host=uri.host?.lowercase().orEmpty()
   require(uri.scheme=="https" && uri.rawUserInfo==null && uri.port in listOf(-1,443) && uri.rawFragment==null &&
    (host.endsWith(".googlevideo.com") || host.endsWith(".googleusercontent.com"))){"Use an HTTPS Google media playback link"}
   require(!uri.path.orEmpty().lowercase().let{it.endsWith(".mpd") || it.endsWith(".m3u8")} &&
    uri.rawQuery.orEmpty().split('&').none{decode(it.substringBefore('='))=="range"}){"Manifests and partial segments cannot be handed off"}
   return value
  }
  private fun decode(value:String)=URLDecoder.decode(value,"UTF-8")
  fun parse(value:String):WebVideoLink {
   val uri=URI(value.trim())
   if(uri.scheme=="https")return WebVideoLink(validateUrl(uri.toString()))
   require(uri.scheme=="photosbeam" && uri.host=="stream" && uri.rawUserInfo==null && uri.port==-1 && uri.rawFragment==null){"Paste a PhotosBeam browser stream link"}
   val query=uri.rawQuery.orEmpty().split('&').map{it.substringBefore('=') to decode(it.substringAfter('=',""))}
   require(query.map{it.first}.distinct().size==query.size){"Duplicate stream parameters"}
   val params=query.toMap();val url=validateUrl(params["url"].orEmpty());val mime=params["mime"] ?: "video/mp4"
   require(mime in listOf("video/mp4","video/webm")){"Only progressive MP4 and WebM streams are supported"}
   fun dimension(name:String)=(params[name]?.toIntOrNull() ?: 0).also{require(it in 0..32768)}
   return WebVideoLink(url,mime,dimension("width"),dimension("height"))
  }
 }
}
fun webFile(file:JSONObject)=file.optBoolean("webStream")
fun directFile(file:JSONObject)=nativeFile(file) || webFile(file)
class StreamingSeekUnsupportedException:java.io.IOException("Server ignored a streaming byte range")
fun refusedStreamingSeek(code:Int,range:String?):Boolean=code==200 && range!=null &&
 Regex("bytes=([0-9]+)-.*").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()?.let{it>0}==true
fun hasStreamingSeekFailure(error:Throwable)=generateSequence(error){it.cause}.take(12).any{it is StreamingSeekUnsupportedException}

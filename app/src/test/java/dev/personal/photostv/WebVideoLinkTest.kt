package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class WebVideoLinkTest {
 private val url="https://r1.googlevideo.com/videoplayback?sig=private%2Fvalue&x=1"
 @Test fun preservesSignedQueryAndMetadataThroughDeepLink(){
  val parsed=WebVideoLink.parse("photosbeam://stream?url="+URLEncoder.encode(url,"UTF-8")+"&mime=video/webm&width=3840&height=2160")
  assertEquals(url,parsed.url);assertEquals(3840,parsed.width);assertEquals("video/webm",parsed.mime)
  validateSelection(parsed.payload())
 }
 @Test fun rejectsNonMediaManifestsSegmentsAndCredentialUrls(){
  for(value in listOf("http://r1.googlevideo.com/video","https://googlevideo.com.evil.org/video","https://evilgooglevideo.com/video","https://user:pass@r1.googlevideo.com/video","https://r1.googlevideo.com:8443/video","https://r1.googlevideo.com/movie.mpd","https://r1.googlevideo.com/movie.m3u8","https://r1.googlevideo.com/video?range=0-40","https://r1.googlevideo.com/video?%72ange=0-40","blob:https://photos.google.com/a","https://127.0.0.1/video","https://r1.googlevideo.com/video#token")){
   assertThrows(IllegalArgumentException::class.java){WebVideoLink.parse(value)}
  }
 }
 @Test fun requiresExplicitWebNamespaceAndNoNativeIdentity(){
  val payload=WebVideoLink(url).payload()
  payload.put("source","google")
  assertThrows(IllegalArgumentException::class.java){validateSelection(payload)}
  payload.put("source","web").getJSONArray("items").getJSONObject(0).getJSONObject("mediaFile").put("nativePin","a".repeat(64))
  assertThrows(IllegalArgumentException::class.java){validateSelection(payload)}
 }
 @Test fun validatesIntentMimeDimensionsAndDuplicates(){
  val base="photosbeam://stream?url="+URLEncoder.encode(url,"UTF-8")
  for(suffix in listOf("&mime=application/javascript","&width=-1","&height=999999","&url=second"))assertThrows(IllegalArgumentException::class.java){WebVideoLink.parse(base+suffix)}
 }
 @Test fun streamingGuardStopsIgnoredTailRequestsButPermitsRealRangesAndInitialResponses(){
  assertTrue(refusedStreamingSeek(200,"bytes=604197510-"));assertTrue(refusedStreamingSeek(200,"bytes=44-100"))
  assertFalse(refusedStreamingSeek(206,"bytes=44-100"));assertFalse(refusedStreamingSeek(200,"bytes=0-"));assertFalse(refusedStreamingSeek(200,null))
  assertTrue(hasStreamingSeekFailure(java.io.IOException(StreamingSeekUnsupportedException())))
 }
}

package dev.personal.photostv
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class MediaViewerTest{
 @Test fun preserves4kPhotoBudget(){assertEquals(PhotoPixels(3840,2160),photoPixels(3840,2160))}
 @Test fun bounds8kDecode(){assertEquals(PhotoPixels(3840,2160),photoPixels(7680,4320))}
 @Test fun preservesPortraitAspect(){assertEquals(PhotoPixels(2160,3840),photoPixels(4320,7680))}
 @Test fun keepsSmallBuffersSmall(){assertEquals(PhotoPixels(1920,1080),photoPixels(1920,1080))}
 @Test fun readsPickerVideoStatusFromCorrectMetadata(){
  val file=JSONObject("""{"mediaFileMetadata":{"videoMetadata":{"processingStatus":"PROCESSING"}}}""")
  assertEquals("PROCESSING",videoReadiness(file));assertEquals("",videoReadiness(JSONObject()))
 }
 @Test fun errorsExcludeProtectedUrlAndToken(){
  val error=java.io.IOException("https://example.invalid/private?token=secret")
  assertEquals("IOException",mediaError(error))
 }
}

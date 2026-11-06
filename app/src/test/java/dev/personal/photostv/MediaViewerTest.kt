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
 @Test fun fullHdQualityCapsLandscapeAndPortrait(){
  assertEquals(PhotoPixels(1920,1080),qualityPixels(PhotoPixels(3840,2160),PhotoQuality.FULL_HD))
  assertEquals(PhotoPixels(1080,1920),qualityPixels(PhotoPixels(2160,3840),PhotoQuality.FULL_HD))
 }
 @Test fun sourceFileUsesDownloadParameter(){assertEquals("https://example.invalid/photo=d",photoRequest("https://example.invalid/photo",PhotoPixels(3840,2160),PhotoQuality.SOURCE_FILE))}
 @Test fun screenQualityUsesExplicitDimensions(){assertEquals("https://example.invalid/photo=w3840-h2160",photoRequest("https://example.invalid/photo",PhotoPixels(3840,2160),PhotoQuality.SCREEN))}
 @Test fun missingSourceDimensionsAreUnknown(){assertEquals("unknown",sourceDimensions(JSONObject()))}
}

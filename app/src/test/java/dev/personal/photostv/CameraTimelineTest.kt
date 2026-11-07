package dev.personal.photostv
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.time.ZoneId
import java.time.Instant
class CameraTimelineTest {
 @Test fun previewsAreBoundedAndNeverUpscale(){
  assertEquals(480 to 320,thumbnailDimensions(600,400));assertEquals(100 to 80,thumbnailDimensions(100,80))
  val portrait=thumbnailDimensions(3000,4000);assertEquals(320,portrait.second);assertTrue(portrait.first<320)
 }
 @Test fun cameraFolderExcludesScreenshotsAndOtherApps(){
  for(path in listOf("DCIM/Camera/","DCIM/Camera/sub/","/storage/emulated/0/DCIM/Camera/a.jpg","DCIM/CAMERA/"))assertTrue(cameraFolder(path))
  for(path in listOf("Pictures/","DCIM/Screenshots/","DCIM/CameraElse/","Pictures/Camera/","DCIM/"))assertFalse(cameraFolder(path))
 }
 @Test fun timestampsPreferCaptureAndFallbackToAddedDate(){assertEquals(123L,cameraTimestamp(123,999));assertEquals(999000L,cameraTimestamp(0,999));assertEquals(0L,cameraTimestamp(0,-1))}
 @Test fun groupingUsesLocalDayAcrossTimezoneAndMidnight(){
  val timestamp=Instant.parse("2026-11-07T23:30:00Z").toEpochMilli()
  assertEquals("2026-11-08",timelineDay(timestamp,ZoneId.of("Europe/Berlin")))
  assertEquals("2026-11-07",timelineDay(timestamp,ZoneId.of("UTC")));assertEquals("Undated",timelineDay(0))
 }
 @Test fun pagingHasNoTwoThousandItemLibraryLimit(){
  assertEquals(100,catalogPageBounds(2000,100,10000).count());assertEquals(listOf(204,205),catalogPageBounds(204,100,206).toList())
  assertTrue(catalogPageBounds(206,100,206).isEmpty());assertEquals(3,catalogPageBounds(Int.MAX_VALUE-3,100,Int.MAX_VALUE).count())
  for(pair in listOf(-1 to 100,207 to 100,0 to 0,0 to 201))assertThrows(IllegalArgumentException::class.java){catalogPageBounds(pair.first,pair.second,206)}
 }
 @Test fun rowMappingPreservesViewingIndicesAfterDateHeaders(){
  val items=listOf(10L,9L,0L).mapIndexed{i,t->JSONObject().put("id","$i").put("taken",t)}
  val rows=timelineTiles(items,true);assertEquals(listOf(0,1,2),rows.filter{it.media!=null}.map{it.index});assertEquals(2,rows.count{it.media==null})
  assertEquals(items,timelineTiles(items,false).map{it.media})
 }
 @Test fun catalogAndThumbnailCannotEscapePinnedNativeOrigin(){
  val file=JSONObject().put("baseUrl","https://192.168.1.2:1234/media/0").put("nativePin","a".repeat(64))
  validateNativeCompanionUrl("https://192.168.1.2:1234/catalog",file,"/catalog")
  for(url in listOf("http://192.168.1.2:1234/catalog","https://192.168.1.3:1234/catalog","https://192.168.1.2:4321/catalog","https://192.168.1.2:1234/catalog?redirect=bad","https://user@192.168.1.2:1234/catalog","https://192.168.1.2:1234/media/0"))assertThrows(IllegalArgumentException::class.java){validateNativeCompanionUrl(url,file,"/catalog")}
  val payload=JSONObject().put("requestId","test").put("token","grant").put("session","native:test").put("source","native").put("items",JSONArray().put(JSONObject().put("type","PHOTO").put("mediaFile",file.put("thumbnailUrl","https://192.168.1.2:1234/thumb/0"))))
  payload.put("cameraCatalog",JSONObject().put("baseUrl","https://192.168.1.2:1234/catalog").put("total",9000).put("nextOffset",1));validateSelection(payload)
  payload.getJSONObject("cameraCatalog").put("nextOffset",100)
  assertThrows(IllegalArgumentException::class.java){validateSelection(payload)}
 }
 @Test fun catalogBodyIsBoundedWithoutDependingOnNewerInputStreamApis(){
  assertArrayEquals(byteArrayOf(1,2),readBounded(byteArrayOf(1,2).inputStream(),2))
  assertThrows(IllegalArgumentException::class.java){readBounded(ByteArray(10000).inputStream(),8192)}
 }
}

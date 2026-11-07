package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test

class ViewerControlsTest {
 @Test fun videoDirectionsSeparateItemNavigationFromSeeking(){
  assertEquals(ViewerDirection.PREVIOUS,viewerDirection(19,true))
  assertEquals(ViewerDirection.NEXT,viewerDirection(20,true))
  assertEquals(ViewerDirection.SEEK_BACK,viewerDirection(21,true))
  assertEquals(ViewerDirection.SEEK_FORWARD,viewerDirection(22,true))
  assertNull(viewerDirection(23,true))
 }
 @Test fun photosKeepBothDirectionalNavigationOptions(){
  assertEquals(ViewerDirection.PREVIOUS,viewerDirection(19,false));assertEquals(ViewerDirection.NEXT,viewerDirection(20,false))
  assertEquals(ViewerDirection.PREVIOUS,viewerDirection(21,false));assertEquals(ViewerDirection.NEXT,viewerDirection(22,false))
 }
 @Test fun seekingClampsAtBothEndsAndHandlesUnknownDuration(){
  assertEquals(0L,seekPosition(5000,60000,-10000))
  assertEquals(60000L,seekPosition(55000,60000,10000))
  assertEquals(65000L,seekPosition(55000,-1,10000))
  assertEquals(Long.MAX_VALUE,seekPosition(Long.MAX_VALUE-1,-1,10000))
 }
 @Test fun hardwareSupportedTrackWinsOverSoftwareOnly4k(){
  val hardware=VideoTrackPreference(true,PlaybackQuality(1920,1080,60f,20_000_000),"video/avc")
  val software=VideoTrackPreference(false,PlaybackQuality(3840,2160,60f,40_000_000),"video/av01")
  assertTrue(hardware>software)
 }
 @Test fun matchingHardwareTracksPreferResolutionThenFpsThenCodec(){
  fun track(w:Int,h:Int,fps:Float,mime:String,bits:Int=30_000_000)=VideoTrackPreference(true,PlaybackQuality(w,h,fps,bits),mime)
  assertTrue(track(3840,2160,30f,"video/avc")>track(1920,1080,60f,"video/av01"))
  assertTrue(track(3840,2160,60f,"video/hevc")>track(3840,2160,30f,"video/av01"))
  assertTrue(track(3840,2160,60f,"video/hevc")>track(3840,2160,60f,"video/avc",50_000_000))
 }
 @Test fun protocolCompatibilityDoesNotChangeMediaQuality(){
  assertEquals(listOf(okhttp3.Protocol.HTTP_1_1),videoHttpClient(true).protocols)
  assertTrue(videoHttpClient().protocols.contains(okhttp3.Protocol.HTTP_2))
  assertFalse(videoHttpClient().retryOnConnectionFailure)
 }
}

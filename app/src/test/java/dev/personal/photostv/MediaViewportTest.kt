package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MediaViewportTest {
 @Test fun fitsLandscapeAndPortraitWithoutCropping(){
  assertEquals(PhotoBounds(0f,0f,3840f,2160f),photoBounds(3840f,2160f,4000f,2250f,MediaViewport()))
  val portrait=photoBounds(3840f,2160f,3000f,4000f,MediaViewport())
  assertEquals(1620f,portrait.width,.01f);assertEquals(1110f,portrait.left,.01f);assertEquals(0f,portrait.top,.01f)
 }
 @Test fun panningNeverExposesEmptyEdgesAfterZoom(){
  for(aspect in listOf(.5f,.75f,1f,16f/9,3f))for(scale in listOf(1f,1.5f,2f,4f))for(pan in listOf(-9f,0f,9f)){
   val b=photoBounds(1920f,1080f,aspect*1000,1000f,MediaViewport(scale,pan,pan))
   if(b.width>=1920f){assertTrue(b.left<=.01f);assertTrue(b.left+b.width>=1919.99f)}else assertEquals((1920-b.width)/2,b.left,.01f)
   if(b.height>=1080f){assertTrue(b.top<=.01f);assertTrue(b.top+b.height>=1079.99f)}else assertEquals((1080-b.height)/2,b.top,.01f)
  }
 }
 @Test fun zoomIsBoundedAndFitResetsPanning(){
  val zoom=MediaViewport().change(ViewportChange(4f,2f,-2f),16f/9,16f/9)
  assertEquals(MediaViewport(4f,1.5f,-1.5f),zoom)
  assertEquals(MediaViewport(),zoom.change(ViewportChange(.25f),16f/9,16f/9))
 }
 @Test fun portraitPanIsDisabledAlongAnAxisThatStillFits(){
  val portrait=MediaViewport(2f,1f,1f).bounded(.75f,16f/9)
  assertEquals(0f,portrait.x,0f);assertEquals(.5f,portrait.y,0f)
 }
 @Test fun rejectsInvalidNetworkTransforms(){
  for(bad in listOf("NaN","Infinity","0","99")){
   val input=JSONObject().put("scale",bad).put("dx",0).put("dy",0)
   assertThrows(IllegalArgumentException::class.java){ViewportChange.parse(input)}
  }
  assertThrows(IllegalArgumentException::class.java){ViewportChange.parse(JSONObject().put("scale",1).put("dx",3).put("dy",0))}
 }
 @Test fun coalescingPreservesPanThenZoomOrderAndClearsIdleInput(){
  val changes=ViewportAccumulator();assertNull(changes.take())
  changes.add(1f,.1f,0f);changes.add(1.5f,0f,0f)
  val combined=changes.take()!!
  assertEquals(1.5f,combined.scale,0f);assertEquals(.15f,combined.dx,.0001f)
  val initial=MediaViewport(2f,.2f,0f)
  val sequential=initial.change(ViewportChange(dx=.1f),16f/9,16f/9).change(ViewportChange(1.5f),16f/9,16f/9)
  assertEquals(sequential,initial.change(combined,16f/9,16f/9));assertNull(changes.take())
 }
 @Test fun warmthOnlyAttenuatesColorChannelsAndOffIsIdentity(){
  assertArrayEquals(floatArrayOf(1f,1f,1f),warmthChannels(0f),0f)
  val tint=warmthChannels(1f)
  assertTrue(tint[0]>=tint[1] && tint[1]>tint[2]);assertTrue(tint.all{it in 0f..1f})
  assertArrayEquals(floatArrayOf(0f,0f,0f),tint.map{it*0f}.toFloatArray(),0f)
 }
 @Test fun zoomPresetsReturnToFit(){assertEquals(1.5f,MediaViewport().nextZoom(),0f);assertEquals(1f,MediaViewport(4f).nextZoom(),0f)}
}

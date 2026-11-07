package dev.personal.photostv

import org.json.JSONObject
import kotlin.math.max

/** Pan is measured in viewport widths/heights, so phone input is independent of display pixels. */
data class ViewportChange(val scale:Float=1f,val dx:Float=0f,val dy:Float=0f){
 fun json()=JSONObject().put("scale",scale.toDouble()).put("dx",dx.toDouble()).put("dy",dy.toDouble())
 companion object {
  fun parse(j:JSONObject)=ViewportChange(j.getDouble("scale").toFloat(),j.getDouble("dx").toFloat(),j.getDouble("dy").toFloat()).also{
   require(it.scale.isFinite() && it.scale in 0.25f..4f)
   require(it.dx.isFinite() && it.dx in -2f..2f && it.dy.isFinite() && it.dy in -2f..2f)
  }
 }
}
data class MediaViewport(val scale:Float=1f,val x:Float=0f,val y:Float=0f){
 fun change(input:ViewportChange,mediaAspect:Float,displayAspect:Float):MediaViewport{
  val zoom=(scale*input.scale).coerceIn(1f,4f)
  val ratio=zoom/scale
  return MediaViewport(zoom,x*ratio+input.dx,y*ratio+input.dy).bounded(mediaAspect,displayAspect)
 }
 fun bounded(mediaAspect:Float,displayAspect:Float):MediaViewport{
  val z=scale.takeIf{it.isFinite()}?.coerceIn(1f,4f) ?: 1f
  val screen=displayAspect.takeIf{it.isFinite() && it>0} ?: (16f/9f)
  val aspect=mediaAspect.takeIf{it.isFinite() && it>0} ?: screen
  val fitWidth=minOf(1f,aspect/screen);val fitHeight=minOf(1f,screen/aspect)
  val maxX=max(0f,(fitWidth*z-1f)/2f);val maxY=max(0f,(fitHeight*z-1f)/2f)
  return MediaViewport(z,if(maxX==0f)0f else x.takeIf{it.isFinite()}?.coerceIn(-maxX,maxX) ?: 0f,if(maxY==0f)0f else y.takeIf{it.isFinite()}?.coerceIn(-maxY,maxY) ?: 0f)
 }
 fun nextZoom()=listOf(1f,1.5f,2f,3f,4f).firstOrNull{it>scale+0.01f} ?: 1f
}
data class PhotoBounds(val left:Float,val top:Float,val width:Float,val height:Float)
fun photoBounds(width:Float,height:Float,imageWidth:Float,imageHeight:Float,viewport:MediaViewport):PhotoBounds{
 require(width>0 && height>0 && imageWidth>0 && imageHeight>0)
 val state=viewport.bounded(imageWidth/imageHeight,width/height)
 val fit=minOf(width/imageWidth,height/imageHeight)
 val w=imageWidth*fit*state.scale;val h=imageHeight*fit*state.scale
 return PhotoBounds((width-w)/2+state.x*width,(height-h)/2+state.y*height,w,h)
}
/** Combine gestures while one LAN request is in flight; never queue individual pointer events. */
class ViewportAccumulator {
 private var pending=ViewportChange()
 @Synchronized fun add(scale:Float,dx:Float,dy:Float){
  if(!scale.isFinite() || scale<=0 || !dx.isFinite() || !dy.isFinite())return
  pending=ViewportChange((pending.scale*scale).coerceIn(.25f,4f),(pending.dx*scale+dx).coerceIn(-2f,2f),(pending.dy*scale+dy).coerceIn(-2f,2f))
 }
 @Synchronized fun take():ViewportChange?=pending.takeUnless{it==ViewportChange()}.also{pending=ViewportChange()}
}
fun warmthChannels(strength:Float):FloatArray {
 val amount=strength.takeIf{it.isFinite()}?.coerceIn(0f,1f) ?: 0f
 return floatArrayOf(1f,1f-.14f*amount,1f-.5f*amount)
}
fun warmthLabel(strength:Float)=when{strength<=0f->"Off";strength<.5f->"Mild";strength<.9f->"Warm";else->"Strong"}
fun nextWarmth(strength:Float)=when{strength<=0f->.35f;strength<.5f->.7f;strength<.9f->1f;else->0f}

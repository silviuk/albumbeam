package dev.personal.photostv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only indexed local DCIM/Camera media; no cloud picker/provider enumeration. */
data class CameraEntry(val uri:Uri,val mime:String,val taken:Long,val width:Int,val height:Int,val bytes:Long,val duration:Long)
fun cameraTimestamp(taken:Long,addedSeconds:Long)=if(taken>0)taken else addedSeconds.coerceAtLeast(0)*1000
fun cameraFolder(path:String)=path.replace('\\','/').trim('/').lowercase(java.util.Locale.ROOT).let{it=="dcim/camera" || it.startsWith("dcim/camera/") || it.contains("/dcim/camera/") || it.endsWith("/dcim/camera")}
fun timelineDay(timestamp:Long,zone:ZoneId=ZoneId.systemDefault()):String=if(timestamp>0)Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString() else "Undated"
fun timelineLabel(day:String):String=runCatching{java.time.LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}.getOrDefault(day)
fun cameraPermissions(sdk:Int):Array<String> = when{
 sdk>=34->arrayOf("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VIDEO","android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
 sdk>=33->arrayOf("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VIDEO")
 else->arrayOf("android.permission.READ_EXTERNAL_STORAGE")
}
fun catalogPageBounds(offset:Int,limit:Int,total:Int):IntRange {
 require(offset in 0..total && limit in 1..200)
 return offset until minOf(total.toLong(),offset.toLong()+limit).toInt()
}
fun readCameraTimeline(context:Context):List<CameraEntry>{
 val output=mutableListOf<CameraEntry>()
 // Query each collection separately, so images-only or videos-only grants work too.
 for(video in listOf(false,true)){
  val collection=if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val folder=if(Build.VERSION.SDK_INT>=29)MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
  val columns=mutableListOf("_id","mime_type","datetaken","date_added","width","height","_size",folder)
  if(video)columns+="duration"
  val selection=if(Build.VERSION.SDK_INT>=29)"$folder LIKE ? AND is_pending=0" else "$folder LIKE ?"
  val argument=if(Build.VERSION.SDK_INT>=29)"DCIM/Camera/%" else "%/DCIM/Camera/%"
  try{context.contentResolver.query(collection,columns.toTypedArray(),selection,arrayOf(argument),null)?.use{cursor->
   fun number(name:String)=cursor.getColumnIndex(name).let{if(it<0 || cursor.isNull(it))0L else cursor.getLong(it)}
   while(cursor.moveToNext()){
    if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
    val path=cursor.getString(cursor.getColumnIndexOrThrow(folder)).orEmpty();if(!cameraFolder(path))continue
    val mime=cursor.getString(cursor.getColumnIndexOrThrow("mime_type")).orEmpty()
    if(!(if(video)mime.startsWith("video/")else mime.startsWith("image/")))continue
    output+=CameraEntry(ContentUris.withAppendedId(collection,number("_id")),mime,cameraTimestamp(number("datetaken"),number("date_added")),number("width").toInt(),number("height").toInt(),number("_size"),if(video)number("duration")else 0)
   }
  }}catch(e:SecurityException){AppLog.add("Phone camera: ${if(video)"video" else "photo"} library access unavailable")}
 }
 return output.sortedWith(compareByDescending<CameraEntry>{it.taken}.thenByDescending{it.uri.lastPathSegment?.toLongOrNull() ?: 0})
}
/** Validate a catalog or thumbnail against the already pinned native media origin. */
fun validateNativeCompanionUrl(url:String,media:JSONObject,pathPattern:String){
 val source=java.net.URI(media.getString("baseUrl"));val target=java.net.URI(url)
 require(target.scheme==source.scheme && target.host==source.host && target.port==source.port && target.rawUserInfo==null && target.rawQuery==null && target.rawFragment==null && target.path.matches(Regex(pathPattern)))
}

data class TimelineTile(val media:JSONObject?,val index:Int,val day:String)
fun timelineTiles(items:List<JSONObject>,dated:Boolean):List<TimelineTile> = buildList {
 var previous:String?=null
 items.forEachIndexed{index,media->
  val day=timelineDay(media.optLong("taken"))
  if(dated && day!=previous)add(TimelineTile(null,index,day))
  add(TimelineTile(media,index,day));previous=day
 }
}
fun readBounded(input:java.io.InputStream,maximum:Int):ByteArray {
 require(maximum>0)
 val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
 while(true){val count=input.read(buffer,0,minOf(buffer.size,maximum-output.size()+1));if(count<0)break
  require(output.size()+count<=maximum){"Response too large"};output.write(buffer,0,count)
 }
 return output.toByteArray()
}

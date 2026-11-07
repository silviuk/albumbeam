package dev.personal.photostv

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import okhttp3.Request
import org.json.JSONObject

/** Run on Android: real MediaStore, original bytes, thumbnails and HTTP paging. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class CameraFeedTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun get(server:NativeMediaServer,payload:JSONObject,path:String,range:String?=null,auth:Boolean=true):okhttp3.Response {
  val builder=Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path")
  if(auth)builder.header("Authorization","Bearer ${payload.getString("token")}")
  if(range!=null)builder.header("Range",range)
  return okhttp3.OkHttpClient().newCall(builder.build()).execute()
 }
 @Test fun pagesBeyondPickerLimitAndRequiresAuthorization(){
  val file=java.io.File(context.cacheDir,"camera-page-fixture.jpg");file.writeBytes(ByteArray(1024){it.toByte()})
  val entries=(0 until 2105).map{CameraEntry(Uri.fromFile(file),"image/jpeg",2105L-it,640,480,1024,0)}
  val server=NativeMediaServer(context,entries.map{it.uri},false,entries)
  try{server.start();val payload=server.selection("127.0.0.1");validateSelection(payload)
   assertEquals(100,payload.getJSONArray("items").length());assertEquals(2105,payload.getJSONObject("cameraCatalog").getInt("total"))
   get(server,payload,"/catalog?offset=2000&limit=100").use{val page=JSONObject(it.body!!.string());assertEquals(100,page.getJSONArray("items").length());assertEquals("2000",page.getJSONArray("items").getJSONObject(0).getString("id"));assertEquals(2100,page.getInt("nextOffset"))}
   get(server,payload,"/catalog?offset=2100&limit=100").use{assertEquals(5,JSONObject(it.body!!.string()).getJSONArray("items").length())}
   get(server,payload,"/catalog?offset=-1").use{assertEquals(400,it.code)}
   get(server,payload,"/catalog",auth=false).use{assertEquals(403,it.code)}
   get(server,payload,"/media/0","bytes=1000-1023").use{assertEquals(206,it.code);assertEquals("bytes 1000-1023/1024",it.header("Content-Range"));assertArrayEquals(file.readBytes().copyOfRange(1000,1024),it.body!!.bytes())}
  }finally{server.stop();file.delete()}
 }
 @Test fun localCameraOrderThumbnailsAndOriginalPixels(){
  val created=mutableListOf<Uri>();val bytes=java.io.ByteArrayOutputStream().use{out->val image=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLUE);image.compress(Bitmap.CompressFormat.JPEG,100,out);image.recycle();out.toByteArray()}
  fun insert(folder:String,taken:Long):Uri {
   val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"PhotosBeam-${java.util.UUID.randomUUID()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,folder);put(MediaStore.Images.Media.DATE_TAKEN,taken);put(MediaStore.Images.Media.IS_PENDING,1)}
   val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;created+=uri
   context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
   context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);return uri
  }
  try{
   val old=insert("DCIM/Camera/PhotosBeamTests/",1_700_000_000_000);val recent=insert("DCIM/Camera/PhotosBeamTests/",1_800_000_000_000);val excluded=insert("Pictures/PhotosBeamTests/",1_900_000_000_000)
   val entries=readCameraTimeline(context);val oldIndex=entries.indexOfFirst{it.uri==old};val index=entries.indexOfFirst{it.uri==recent}
   assertTrue(index>=0);assertTrue(oldIndex>index);assertFalse(entries.any{it.uri==excluded})
   val server=NativeMediaServer(context,entries.map{it.uri},false,entries)
   try{server.start();val payload=server.selection("127.0.0.1")
    get(server,payload,"/thumb/$index").use{assertEquals(200,it.code);val data=it.body!!.bytes();val thumb=BitmapFactory.decodeByteArray(data,0,data.size);assertNotNull(thumb);assertTrue(thumb.width<=480 && thumb.height<=320);thumb.recycle()}
    get(server,payload,"/media/$index").use{assertEquals(200,it.code);assertArrayEquals(bytes,it.body!!.bytes())}
    get(server,payload,"/media/$index","bytes=${bytes.size-32}-").use{assertEquals(206,it.code);assertArrayEquals(bytes.copyOfRange(bytes.size-32,bytes.size),it.body!!.bytes())}
   }finally{server.stop()}
  }finally{created.forEach{context.contentResolver.delete(it,null,null)}}
 }
}

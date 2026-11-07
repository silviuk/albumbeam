package dev.personal.photostv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RememberedPairingTest {
 @Test fun encryptedDeviceCredentialsSurviveStoreRecreation(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="instrumentation-only"
  val store=PairingStore(context)
  val identity=DeviceIdentity.create()
  try{
   store.save(name,identity.json())
   val raw=context.getSharedPreferences("paired-device",0).getString(name,"")!!
   assertFalse(raw.contains(identity.auth));assertFalse(raw.contains("PRIVATE KEY"))
   val loaded=DeviceIdentity.parse(PairingStore(context).read(name)!!)
   assertEquals(identity.auth,loaded.auth)
   assertArrayEquals(identity.held.certificate.encoded,loaded.held.certificate.encoded)
   context.getSharedPreferences("paired-device",0).edit().putString(name,"corrupted ciphertext").commit()
   assertNull(PairingStore(context).read(name))
  }finally{store.clear(name)}
 }
 @Test fun phoneStreamingKeepsBytesAndHonorsRangesInBothTransportModes(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val file=java.io.File(context.cacheDir,"native-media-test.jpg")
  val bytes=ByteArray(2048){(it%251).toByte()};file.writeBytes(bytes)
  try{
   for(encrypted in listOf(true,false)){
    if(!encrypted && BuildConfig.FLAVOR!="tv")continue // Only the TV manifest opts into LAN cleartext.
    val server=NativeMediaServer(context,listOf(android.net.Uri.fromFile(file)),encrypted)
    try{
     server.start()
     val selection=server.selection("127.0.0.1")
     val item=selection.getJSONArray("items").getJSONObject(0).getJSONObject("mediaFile")
     val client=mediaClient(item)
     val url=item.getString("baseUrl")
     val request=okhttp3.Request.Builder().url(url).header("Authorization","Bearer ${selection.getString("token")}")
     client.newCall(request.build()).execute().use{assertEquals(200,it.code);assertArrayEquals(bytes,it.body!!.bytes())}
     client.newCall(request.header("Range","bytes=100-199").build()).execute().use{assertEquals(206,it.code);assertEquals("bytes 100-199/2048",it.header("Content-Range"));assertArrayEquals(bytes.copyOfRange(100,200),it.body!!.bytes())}
     client.newCall(request.header("Range","bytes=99999-").build()).execute().use{assertEquals(416,it.code)}
     client.newCall(request.removeHeader("Authorization").build()).execute().use{assertEquals(403,it.code)}
    }finally{server.stop()}
   }
  }finally{file.delete()}
 }
}

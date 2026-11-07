package dev.personal.photostv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only device credentials are persisted. Google tokens and media grants stay in memory. */
class PairingStore(context:Context){
 private val preferences=context.getSharedPreferences("paired-device",Context.MODE_PRIVATE)
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey("albumbeam-storage-v1",null) as? SecretKey)?.let{return it}
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder("albumbeam-storage-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun read(name:String):JSONObject? {
  val encoded=preferences.getString(name,null) ?: return null
  return runCatching{
   val bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP)
   require(bytes.size>28)
   val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(name.toByteArray())}
   JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
  }.getOrElse{AppLog.add("Saved device identity unavailable; pair again");clear(name);null}
 }
 fun save(name:String,value:JSONObject){
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key());updateAAD(name.toByteArray())}
  val encrypted=cipher.iv+cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
  check(preferences.edit().putString(name,android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP)).commit())
 }
 fun clear(name:String){check(preferences.edit().remove(name).commit())}
}
data class DeviceIdentity(val held:HeldCertificate,val auth:String){
 fun json()=JSONObject().put("pem",held.certificatePem()+held.privateKeyPkcs8Pem()).put("auth",auth)
 companion object {
  fun create()=DeviceIdentity(HeldCertificate.Builder().commonName("AlbumBeam device").addSubjectAlternativeName("127.0.0.1")
   .duration(3650,TimeUnit.DAYS).ecdsa256().build(),secret())
  fun parse(json:JSONObject)=DeviceIdentity(HeldCertificate.decode(json.getString("pem")),json.getString("auth").also{require(it.matches(Regex("[a-f0-9]{64}")))})
 }
}

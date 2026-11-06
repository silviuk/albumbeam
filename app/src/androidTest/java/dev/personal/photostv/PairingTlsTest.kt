package dev.personal.photostv

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import org.json.JSONObject

/** Runs on Android: exercises real Keystore signing, TLS, HTTP and pin validation. */
@RunWith(AndroidJUnit4::class)
class PairingTlsTest {
 @Test fun migratesLegacyKeyAndPairsOverPinnedTls() {
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  val old="photos-tv-pair"
  if(ks.containsAlias(old))ks.deleteEntry(old)
  KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply {
   initialize(KeyGenParameterSpec.Builder(old,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
    .setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
  }.generateKeyPair()
  var received=""
  val server=PairServer{received=it.getString("session")}
  try {
   server.start()
   val link=server.link("127.0.0.1")
   val wrongPin=Connection(link.copy(pin="0".repeat(64)))
   assertThrows(java.io.IOException::class.java){wrongPin.pair()}
   val phone=Connection(link)
   phone.probe()
   phone.pair()
   assertTrue(phone.auth.isNotBlank())
   assertFalse(ks.containsAlias(old))
   val payload=JSONObject().put("requestId","test").put("session","selection").put("token","test-only")
   phone.post("session",payload)
   assertEquals("selection",received)
   assertThrows(IllegalArgumentException::class.java){Connection(link).pair()}
  }finally{server.stop()}
 }
}

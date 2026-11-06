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

/** Runs on Android: exercises TLS, HTTP and pin validation on the device provider. */
@RunWith(AndroidJUnit4::class)
class PairingTlsTest {
 @Test fun pairsWithEphemeralIdentityOverPinnedTls() {
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
   val payload=JSONObject().put("requestId","test").put("session","selection").put("token","test-only")
   phone.post("session",payload)
   assertEquals("selection",received)
   assertThrows(IllegalArgumentException::class.java){Connection(link).pair()}
  }finally{server.stop()}
 }
}

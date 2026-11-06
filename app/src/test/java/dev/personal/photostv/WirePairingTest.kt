package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.json.JSONObject

/** Real loopback TCP/TLS/HTTP, not mocked network responses. */
class WirePairingTest {
 private lateinit var oldOutput:(String)->Unit
 @Before fun silenceAndroidLog(){oldOutput=AppLog.output;AppLog.output={}}
 @After fun restoreLog(){AppLog.output=oldOutput}
 @Test fun tlsProbePairTransferAndReplay(){
  var deliveries=0
  val server=PairServer{deliveries++}
  try{
   server.start()
   val link=server.link("127.0.0.1")
   val phone=Connection(link)
   phone.probe() // Must not consume pairing secret.
   phone.pair()
   assertTrue(phone.auth.isNotBlank())
   val body=JSONObject().put("requestId","once").put("session","test-session").put("token","test-token")
   phone.post("session",body);phone.post("session",body)
   assertEquals(1,deliveries)
   assertThrows(IllegalArgumentException::class.java){Connection(link).pair()}
  }finally{server.stop()}
 }
 @Test fun pinMismatchIsRejectedOverRealTls(){
  val server=PairServer{}
  try{
   server.start()
   val link=server.link("127.0.0.1")
   assertThrows(java.io.IOException::class.java){Connection(link.copy(pin="0".repeat(64))).probe()}
   Connection(link).probe()
  }finally{server.stop()}
 }
 @Test fun unrelatedClientCannotTransferSelection(){
  val server=PairServer{fail("Unauthenticated transfer reached application")}
  try{
   server.start()
   val client=Connection(server.link("127.0.0.1"))
   assertThrows(IllegalArgumentException::class.java){client.post("session",JSONObject().put("requestId","unauthorized"))}
  }finally{server.stop()}
 }
 @Test fun manualPairingUsesTlsAndConsumesQrToo(){
  var received=false
  val server=PairServer{received=true}
  try{
   server.start();val link=server.link("127.0.0.1");val phone=Connection(link)
   phone.pair(server.manualCode)
   assertTrue(phone.auth.isNotBlank())
   assertThrows(IllegalArgumentException::class.java){Connection(link).pair()}
   phone.post("session",JSONObject().put("requestId","manual").put("session","test").put("token","test"))
   assertTrue(received)
  }finally{server.stop()}
 }
 @Test fun manualCodeLocksAfterFiveFailuresOverTls(){
  val server=PairServer{}
  try{
   server.start();val link=server.link("127.0.0.1")
   repeat(5){assertThrows(IllegalArgumentException::class.java){Connection(link).pair("!!!!!!")}}
   assertThrows(IllegalArgumentException::class.java){Connection(link).pair(server.manualCode)}
   Connection(link).pair() // QR remains a recovery path.
  }finally{server.stop()}
 }
 @Test fun qrPairingConsumesManualCodeToo(){
  val server=PairServer{}
  try{server.start();val link=server.link("127.0.0.1");Connection(link).pair();assertThrows(IllegalArgumentException::class.java){Connection(link).pair(server.manualCode)}}finally{server.stop()}
 }
 @Test fun eachServerGetsANewPinnedIdentity(){
  val first=PairServer{};val second=PairServer{}
  try{assertNotEquals(first.pin,second.pin)}finally{first.stop();second.stop()}
 }
}

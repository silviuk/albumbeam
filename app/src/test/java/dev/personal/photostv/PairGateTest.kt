package dev.personal.photostv
import org.junit.Assert.*
import org.junit.Test
class PairGateTest {
 @Test fun rejectsWrongAndReplay(){val g=PairGate(); assertFalse(g.consume("wrong"));assertTrue(g.consume(g.code));assertFalse(g.consume(g.code))}
 @Test fun expiresAtFiveMinutes(){var clock=0L;val g=PairGate{clock};clock=300000;assertFalse(g.consume(g.code))}
 @Test fun exactlyOneConcurrentAttemptWins(){
  val g=PairGate(); val wins=java.util.concurrent.atomic.AtomicInteger()
  val threads=(1..16).map{Thread {if(g.consume(g.code))wins.incrementAndGet()}}
  threads.forEach{it.start()}; threads.forEach{it.join()}; assertEquals(1,wins.get())
 }
 @Test fun fingerprintsAreDeterministicAndSensitive(){assertEquals(fingerprint(byteArrayOf(1)),fingerprint(byteArrayOf(1)));assertNotEquals(fingerprint(byteArrayOf(1)),fingerprint(byteArrayOf(2)))}
 @Test fun tlsDiagnosticsIncludeNestedCause(){
  val e=javax.net.ssl.SSLHandshakeException("Connection closed").apply{initCause(java.security.InvalidKeyException("Incompatible digest"))}
  val details=tlsDetails(e)
  assertTrue(details.contains("SSLHandshakeException"));assertTrue(details.contains("InvalidKeyException"));assertTrue(details.contains("Incompatible digest"))
 }
 @Test fun tlsDiagnosticsBoundLongMessages(){assertTrue(tlsDetails(java.io.IOException("x".repeat(10000))).length<=1400)}
}

package dev.personal.photostv

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

fun secret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
class PairGate(private val now: () -> Long = System::currentTimeMillis) {
 val code=secret(); private val deadline=now()+300_000; private var used=false
 @Synchronized fun consume(candidate: String): Boolean {
  if(used || now()>=deadline || !MessageDigest.isEqual(code.toByteArray(),candidate.toByteArray())) return false
  used=true; return true
 }
}
data class Link(val host:String,val port:Int,val pin:String,val code:String) {
 fun json()=JSONObject().put("v",1).put("host",host).put("port",port).put("pin",pin).put("code",code).toString()
 companion object { fun parse(s:String):Link { val j=JSONObject(s); require(j.getInt("v")==1); val host=j.getString("host"); require(host.matches(Regex("[0-9.]+"))); val pin=j.getString("pin"); require(pin.matches(Regex("[a-f0-9]{64}"))); val port=j.getInt("port"); require(port in 1..65535); return Link(host,port,pin,j.getString("code")) } }
}

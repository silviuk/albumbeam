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

/** Six-character manual secret, five attempts and five minutes per TV pairing screen. */
class ManualGate(private val now:()->Long=System::currentTimeMillis){
 val code=buildString{val random=SecureRandom();repeat(6){append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)])}}
 private val deadline=now()+300_000
 private var attempts=0
 private var used=false
 @Synchronized fun consume(candidate:String):Boolean{
  if(used || attempts>=5 || now()>=deadline)return false
  attempts++
  val matches=MessageDigest.isEqual(code.toByteArray(),candidate.trim().uppercase(java.util.Locale.ROOT).toByteArray())
  if(matches)used=true
  return matches
 }
}
fun verification(pin:String)=pin.take(16).uppercase(java.util.Locale.ROOT).chunked(4).joinToString("-")

enum class RemoteAction(val wire:String){
 PREVIOUS("previous"),NEXT("next"),PLAY("play"),PAUSE("pause"),STOP("stop"),START_SLIDESHOW("start-slideshow"),STOP_SLIDESHOW("stop-slideshow");
 companion object{fun parse(value:String)=entries.firstOrNull{it.wire==value}}
}

/** Monotonic timer; a stale hide callback may never hide controls after new input. */
class IdleControls(private val now:()->Long={System.nanoTime()/1_000_000}){
 val timeoutMillis=3000L
 private var generation=0L
 private var lastInput=now()
 @Synchronized fun touch():Long{lastInput=now();return ++generation}
 @Synchronized fun shouldHide(expected:Long,panelOpen:Boolean=false)=!panelOpen && expected==generation && now()-lastInput>=timeoutMillis
}

enum class ViewerDirection{PREVIOUS,NEXT,SEEK_BACK,SEEK_FORWARD}
/** Android DPAD key codes; directional actions are distinct from menu focus navigation. */
fun viewerDirection(keyCode:Int,video:Boolean):ViewerDirection?=when(keyCode){
 19->ViewerDirection.PREVIOUS
 20->ViewerDirection.NEXT
 21->if(video)ViewerDirection.SEEK_BACK else ViewerDirection.PREVIOUS
 22->if(video)ViewerDirection.SEEK_FORWARD else ViewerDirection.NEXT
 else->null
}
fun seekPosition(position:Long,duration:Long,delta:Long):Long {
 val target=if(delta>0 && position>Long.MAX_VALUE-delta)Long.MAX_VALUE else position+delta
 return target.coerceAtLeast(0).coerceAtMost(if(duration>0)duration else Long.MAX_VALUE)
}

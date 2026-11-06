package dev.personal.photostv

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Bounded, memory-only operational events. Callers must never pass credentials or URLs. */
object AppLog {
 private val buffer=MutableStateFlow<List<String>>(emptyList())
 val entries=buffer.asStateFlow()
 internal var output:(String)->Unit={Log.i("AlbumBeam",it);Unit}
 @Synchronized fun add(event:String){
  val line=LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"  "+event
  buffer.value=(buffer.value+line).takeLast(500)
  output(line)
 }
 @Synchronized fun export():String=buildString{
  append("AlbumBeam ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) • ${java.time.Instant.now()}\n")
  val runtime=Runtime.getRuntime()
  append("Heap used/max MiB: ${(runtime.totalMemory()-runtime.freeMemory())/1048576}/${runtime.maxMemory()/1048576}\n")
  append(buffer.value.joinToString("\n"))
 }.takeLast(240_000)
 @Synchronized fun clear(){buffer.value=emptyList()}
}

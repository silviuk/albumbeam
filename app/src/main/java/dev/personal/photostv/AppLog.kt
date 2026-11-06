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
  buffer.value=(buffer.value+line).takeLast(120)
  output(line)
 }
 @Synchronized fun clear(){buffer.value=emptyList()}
}

package dev.personal.photostv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.C
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Verify the default optional fMP4 footer seek is absent in the streaming factory. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingStartupTest{
 private fun firstSeek(extractor:Extractor):Long?{
  val bytes=ByteBuffer.allocate(256).putInt(256).put(byteArrayOf(109,100,97,116)).array()
  var position=0
  val input=DefaultExtractorInput({buffer,offset,length->
   val count=minOf(length,bytes.size-position)
   if(count==0)C.RESULT_END_OF_INPUT else{bytes.copyInto(buffer,offset,position,position+count);position+=count;count}
  },0,bytes.size.toLong())
  extractor.init(object:ExtractorOutput{
   override fun track(id:Int,type:Int):TrackOutput=DiscardingTrackOutput()
   override fun endTracks(){}
   override fun seekMap(map:SeekMap){}
  })
  val holder=PositionHolder()
  try{repeat(10){when(extractor.read(input,holder)){Extractor.RESULT_SEEK->return holder.position;Extractor.RESULT_END_OF_INPUT->return null}};return null}finally{extractor.release()}
 }
 @Test fun skipsOptionalTailScanBeforePlayback(){
  val default=streamingExtractors(false).createExtractors().first{it is FragmentedMp4Extractor}
  val streaming=streamingExtractors().createExtractors().first{it is FragmentedMp4Extractor}
  assertEquals(240L,firstSeek(default))
  assertNull(firstSeek(streaming))
 }
}

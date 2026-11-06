package dev.personal.photostv

import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

/** Avoid optional MP4 footer/GOP scans before streaming the first frame. No media is transcoded. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun streamingExtractors(fastStart:Boolean=true):ExtractorsFactory=if(!fastStart)DefaultExtractorsFactory() else ExtractorsFactory{
 val subtitles=DefaultSubtitleParserFactory()
 val rest=DefaultExtractorsFactory().createExtractors().filter{it !is Mp4Extractor && it !is FragmentedMp4Extractor}
 (listOf(Mp4Extractor(subtitles,0),FragmentedMp4Extractor(subtitles,0))+rest).toTypedArray()
}

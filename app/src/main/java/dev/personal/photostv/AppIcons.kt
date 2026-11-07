package dev.personal.photostv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Simple 24dp navigation glyphs; destination labels provide accessibility names. */
object AppIcons {
 val Photos=ImageVector.Builder("Photos",24.dp,24.dp,24f,24f).apply{path(fill=SolidColor(Color.Black)){
  moveTo(3f,3f);lineTo(21f,3f);lineTo(21f,21f);lineTo(3f,21f);close()
  moveTo(5f,5f);lineTo(5f,17f);lineTo(10f,11f);lineTo(14f,16f);lineTo(17f,12f);lineTo(19f,15f);lineTo(19f,5f);close()
 }}.build()
 val Remote=ImageVector.Builder("Remote",24.dp,24.dp,24f,24f).apply{path(fill=SolidColor(Color.Black)){
  moveTo(7f,2f);lineTo(17f,2f);lineTo(17f,22f);lineTo(7f,22f);close()
  moveTo(9f,4f);lineTo(9f,20f);lineTo(15f,20f);lineTo(15f,4f);close()
  moveTo(11f,7f);lineTo(13f,7f);lineTo(13f,9f);lineTo(11f,9f);close()
  moveTo(10f,12f);lineTo(14f,12f);lineTo(14f,14f);lineTo(10f,14f);close()
 }}.build()
 val Settings=ImageVector.Builder("Settings",24.dp,24.dp,24f,24f).apply{path(fill=SolidColor(Color.Black)){
  moveTo(3f,6f);lineTo(21f,6f);lineTo(21f,8f);lineTo(3f,8f);close()
  moveTo(7f,4f);lineTo(9f,4f);lineTo(9f,10f);lineTo(7f,10f);close()
  moveTo(3f,16f);lineTo(21f,16f);lineTo(21f,18f);lineTo(3f,18f);close()
  moveTo(15f,14f);lineTo(17f,14f);lineTo(17f,20f);lineTo(15f,20f);close()
 }}.build()
}

package dev.personal.photostv

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickerBrowserTest{
 @Test fun edgeChoiceRestrictsOnlyTheSelectionIntent(){
  val link="https://photos.google.com/test-picker"
  val intent=pickerBrowserIntent(link,true)
  assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("com.microsoft.emmx",intent.`package`)
  assertEquals(link,intent.dataString);assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));assertNull(intent.component)
 }
 @Test fun defaultChoiceLeavesPackageUnrestricted(){assertNull(pickerBrowserIntent("https://photos.google.com/test-picker",false).`package`)}
 @Test fun executableAndCleartextLinksAreRejected(){
  for(link in listOf("javascript:alert(1)","http://photos.google.com/test-picker")){
   try{pickerBrowserIntent(link,true);fail("Unsafe link accepted")}catch(expected:IllegalArgumentException){}
  }
 }
}

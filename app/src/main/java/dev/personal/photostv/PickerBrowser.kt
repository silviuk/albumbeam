package dev.personal.photostv

import android.content.Intent
import android.net.Uri

/** Restricts only this VIEW intent to Edge; never changes Android's browser defaults. */
fun pickerBrowserIntent(link:String,edge:Boolean):Intent {
 val uri=Uri.parse(link)
 require(uri.scheme=="https" && !uri.host.isNullOrBlank()){"Selection requires an HTTPS link"}
 return Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).apply{
  if(edge)setPackage("com.microsoft.emmx")
 }
}

package dev.personal.photostv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Custom scanner with an actual camera zoom slider; frames never leave the device. */
class ZoomScannerActivity:ComponentActivity(){
 private val worker=Executors.newSingleThreadExecutor()
 private val finished=AtomicBoolean(false)
 private val scanner=BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
 private var camera by mutableStateOf<Camera?>(null)
 private var zoom by mutableFloatStateOf(1f)
 private var minZoom by mutableFloatStateOf(1f)
 private var maxZoom by mutableFloatStateOf(1f)
 private var status by mutableStateOf("Allow camera access to scan a TV QR. You can zoom in below.")
 private var provider:ProcessCameraProvider?=null
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)showCamera()else{status="Camera permission denied. Use the regular scanner or manual code instead.";showUi(false)}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);showUi(false);permission.launch(Manifest.permission.CAMERA)}
 private fun showCamera(){showUi(true)}
 @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
 private fun bind(view:PreviewView){
  val future=ProcessCameraProvider.getInstance(this)
  future.addListener({
   if(isDestroyed || finished.get())return@addListener
   try{
    val source=future.get();provider=source
    val preview=Preview.Builder().build().apply{surfaceProvider=view.surfaceProvider}
    val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    analysis.setAnalyzer(worker){frame->
     val image=frame.image
     if(image==null || finished.get()){frame.close();return@setAnalyzer}
     try{
      scanner.process(InputImage.fromMediaImage(image,frame.imageInfo.rotationDegrees))
       .addOnSuccessListener{codes->val value=codes.firstOrNull{it.rawValue!=null}?.rawValue
        if(value!=null && finished.compareAndSet(false,true)){setResult(Activity.RESULT_OK,Intent().putExtra("qr",value));finish()}}
       .addOnFailureListener{status="Could not read QR. Adjust zoom or move closer."}
       .addOnCompleteListener{frame.close()}
     }catch(error:Exception){frame.close()}
    }
    source.unbindAll()
    camera=source.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
    camera!!.cameraInfo.zoomState.observe(this){state->minZoom=state.minZoomRatio;maxZoom=minOf(10f,state.maxZoomRatio);zoom=state.zoomRatio.coerceIn(minZoom,maxZoom)}
    status="Aim at the TV QR. Adjust zoom if the code is far away."
    AppLog.add("Phone: local camera scanner opened with zoom controls")
   }catch(error:Exception){status="Camera unavailable. Close and use the regular scanner or manual code.";AppLog.add("Phone: zoom camera failed (${error.javaClass.simpleName})")}
  },ContextCompat.getMainExecutor(this))
 }
 private fun applyZoom(value:Float){val target=value.coerceIn(minZoom,maxZoom);zoom=target;camera?.cameraControl?.setZoomRatio(target)}
 private fun showUi(enabled:Boolean){setContent{MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("Scan TV QR • Zoom",style=MaterialTheme.typography.headlineSmall)
  Text(status)
  if(enabled)AndroidView(factory={PreviewView(it).also{view->view.implementationMode=PreviewView.ImplementationMode.COMPATIBLE;bind(view)}},modifier=Modifier.weight(1f).fillMaxWidth())else Spacer(Modifier.weight(1f))
  Text("Zoom ${"%.1f".format(zoom)}×")
  if(maxZoom>minZoom)Slider(value=zoom,onValueChange={applyZoom(it)},valueRange=minZoom..maxZoom)
  Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
   for(ratio in listOf(1f,2f,4f))Button(enabled=ratio in minZoom..maxZoom && camera!=null,onClick={applyZoom(ratio)}){Text("${ratio.toInt()}×")}
   Button(onClick={finish()}){Text("Cancel")}
  }
 }}}}}
 override fun onDestroy(){finished.set(true);provider?.unbindAll();worker.shutdown();scanner.close();super.onDestroy()}
}

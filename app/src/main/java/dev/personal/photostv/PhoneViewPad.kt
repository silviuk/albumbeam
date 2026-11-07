package dev.personal.photostv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/** Relative touchpad: no photo copies or video preview stream are sent to the phone. */
@Composable fun PhoneViewPad(connection:Connection?,onError:(String)->Unit){
 val changes=remember{ViewportAccumulator()}
 val wake=remember{Channel<Unit>(Channel.CONFLATED)}
 val errorCallback by rememberUpdatedState(onError)
 DisposableEffect(Unit){onDispose{wake.close()}}
 LaunchedEffect(connection){
  for(ignored in wake){
   delay(125) // At most eight requests per second; one request in flight, with accumulated input.
   val input=changes.take() ?: continue
   val paired=connection ?: continue
   try{withContext(Dispatchers.IO){paired.post("viewport",input.json().put("requestId",UUID.randomUUID().toString()))}}
   catch(e:CancellationException){throw e}
   catch(e:Exception){errorCallback("Zoom control unavailable. Check the TV connection and update both apps.")}
  }
 }
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("Zoom & pan",style=MaterialTheme.typography.titleLarge)
  Text("Open a photo or video on the TV. Pinch here to zoom; drag to move the image. Watch the TV as you move.")
  Box(Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh,RoundedCornerShape(24.dp))
   .pointerInput(connection){if(connection!=null)detectTransformGestures{_,pan,zoom,_->
    if(zoom!=1f || pan.x!=0f || pan.y!=0f){changes.add(zoom,pan.x/size.width.coerceAtLeast(1),pan.y/size.height.coerceAtLeast(1));wake.trySend(Unit)}
   }},contentAlignment=Alignment.Center){Text(if(connection==null)"Connect to TV first" else "Pinch to zoom • Drag to pan",style=MaterialTheme.typography.labelLarge)}
 }
}

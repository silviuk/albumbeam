package dev.personal.photostv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Discovery data is untrusted until the user compares the verification value on both screens. */
data class NearbyTV(val name:String,val link:Link)
class NearbyPairing(context:Context){
 private val manager=context.getSystemService(Context.NSD_SERVICE) as NsdManager
 private val main=Handler(Looper.getMainLooper())
 private var registration:NsdManager.RegistrationListener?=null
 private var discovery:NsdManager.DiscoveryListener?=null
 private var active=false
 private var generation=0
 private var resolving=false
 private val queue=java.util.ArrayDeque<NsdServiceInfo>()
 fun advertise(port:Int,pin:String){
  stopAdvertising()
  val listener=object:NsdManager.RegistrationListener{
   override fun onServiceRegistered(info:NsdServiceInfo){AppLog.add("TV: nearby pairing discovery available")}
   override fun onRegistrationFailed(info:NsdServiceInfo,code:Int){AppLog.add("TV: discovery registration failed ($code); QR pairing still available")}
   override fun onServiceUnregistered(info:NsdServiceInfo){}
   override fun onUnregistrationFailed(info:NsdServiceInfo,code:Int){}
  }
  registration=listener
  manager.registerService(NsdServiceInfo().apply{serviceName="AlbumBeam TV";serviceType="_albumbeam._tcp.";setPort(port);setAttribute("pin",pin)},NsdManager.PROTOCOL_DNS_SD,listener)
 }
 fun stopAdvertising(){registration?.let{runCatching{manager.unregisterService(it)}};registration=null}
 fun discover(found:(NearbyTV)->Unit){
  stopDiscovery();active=true;val scan=++generation
  fun next(){
   if(!active || generation!=scan || resolving || queue.isEmpty())return
   resolving=true;val item=queue.removeFirst()
   manager.resolveService(item,object:NsdManager.ResolveListener{
    override fun onResolveFailed(info:NsdServiceInfo,error:Int){main.post{if(generation==scan){resolving=false;next()}}}
    override fun onServiceResolved(info:NsdServiceInfo){main.post{
     if(!active || generation!=scan)return@post
     resolving=false
     val host=if(android.os.Build.VERSION.SDK_INT>=34)info.hostAddresses.firstOrNull{it is Inet4Address} else info.host
     val pin=info.attributes["pin"]?.toString(Charsets.UTF_8).orEmpty()
     if(host is Inet4Address && pin.matches(Regex("[a-f0-9]{64}")))found(NearbyTV(info.serviceName,Link(host.hostAddress!!,info.port,pin,"")))
     next()
    }}
   })
  }
  val listener=object:NsdManager.DiscoveryListener{
   override fun onDiscoveryStarted(type:String){AppLog.add("Phone: searching for nearby AlbumBeam TVs")}
   override fun onStartDiscoveryFailed(type:String,error:Int){AppLog.add("Phone: discovery failed ($error); use QR pairing")}
   override fun onStopDiscoveryFailed(type:String,error:Int){}
   override fun onDiscoveryStopped(type:String){}
   override fun onServiceLost(info:NsdServiceInfo){}
   override fun onServiceFound(info:NsdServiceInfo){main.post{if(active && generation==scan){queue.add(info);next()}}}
  }
  discovery=listener
  manager.discoverServices("_albumbeam._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)
 }
 fun stopDiscovery(){active=false;generation++;queue.clear();resolving=false;discovery?.let{runCatching{manager.stopServiceDiscovery(it)}};discovery=null}
 fun close(){stopDiscovery();stopAdvertising()}
}

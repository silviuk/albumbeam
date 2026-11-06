package dev.personal.photostv

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** One explicit identity; no provider enumeration or silent skipping of keystore entries. */
class PairingKeyManager(
 private val identity:String,
 private val key:PrivateKey,
 private val chain:Array<X509Certificate>,
 private val selected:(Boolean)->Unit = {}
):X509ExtendedKeyManager(){
 init {require(chain.isNotEmpty());require(chain[0].publicKey.algorithm==key.algorithm)}
 private fun matches(type:String?) = type==key.algorithm || type=="${key.algorithm}_${key.algorithm}"
 override fun getServerAliases(keyType:String?,issuers:Array<out Principal>?) = if(matches(keyType)) arrayOf(identity) else null
 override fun chooseServerAlias(keyType:String?,issuers:Array<out Principal>?,socket:Socket?):String? {
  val result=if(matches(keyType)) identity else null
  selected(result!=null)
  return result
 }
 override fun chooseEngineServerAlias(keyType:String?,issuers:Array<out Principal>?,engine:SSLEngine?)=chooseServerAlias(keyType,issuers,null)
 override fun getCertificateChain(alias:String?)=if(alias==identity)chain.copyOf() else null
 override fun getPrivateKey(alias:String?)=if(alias==identity)key else null
 override fun getClientAliases(keyType:String?,issuers:Array<out Principal>?):Array<String>?=null
 override fun chooseClientAlias(keyType:Array<out String>?,issuers:Array<out Principal>?,socket:Socket?):String?=null
 override fun chooseEngineClientAlias(keyType:Array<out String>?,issuers:Array<out Principal>?,engine:SSLEngine?):String?=null
}

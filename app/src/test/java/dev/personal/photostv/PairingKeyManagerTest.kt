package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PairingKeyManagerTest {
 private val certificate=javaClass.getResourceAsStream("/pairing-test-cert.pem").use {
  CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
 }
 private val opaqueKey=object:PrivateKey{
  override fun getAlgorithm()="RSA"
  override fun getFormat():String?=null
  override fun getEncoded():ByteArray?=null
 }
 private fun manager()=PairingKeyManager("paired-tv",opaqueKey,arrayOf(certificate))
 @Test fun selectsOnlyMatchingServerKey(){
  val m=manager()
  assertEquals("paired-tv",m.chooseServerAlias("RSA",null,null))
  assertEquals("paired-tv",m.chooseEngineServerAlias("RSA",null,null))
  assertNull(m.chooseServerAlias("EC",null,null))
 }
 @Test fun retainsOpaquePrivateKeyWithoutExport(){
  val m=manager()
  assertSame(opaqueKey,m.getPrivateKey("paired-tv"))
  assertNull(m.getPrivateKey("other"))
  assertNull(m.getCertificateChain("other"))
 }
 @Test fun certificateChainIsDefensivelyCopied(){
  val m=manager()
  assertNotSame(m.getCertificateChain("paired-tv"),m.getCertificateChain("paired-tv"))
  assertEquals(certificate,m.getCertificateChain("paired-tv")!![0])
 }
 @Test fun neverOffersClientCredentials(){
  val m=manager()
  assertNull(m.chooseClientAlias(arrayOf("RSA"),null,null))
  assertNull(m.chooseEngineClientAlias(arrayOf("RSA"),null,null))
 }
 @Test fun rejectsEmptyChain(){assertThrows(IllegalArgumentException::class.java){PairingKeyManager("paired-tv",opaqueKey,emptyArray())}}
}

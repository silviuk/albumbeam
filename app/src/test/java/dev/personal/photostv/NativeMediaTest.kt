package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test

class NativeMediaTest {
 @Test fun supportsClosedOpenAndSuffixRanges(){
  assertEquals(ByteRange(10,19),ByteRange.parse("bytes=10-19",100))
  assertEquals(ByteRange(90,99),ByteRange.parse("bytes=90-",100))
  assertEquals(ByteRange(90,99),ByteRange.parse("bytes=-10",100))
  assertEquals(ByteRange(0,99),ByteRange.parse("bytes=-200",100))
  assertEquals(ByteRange(90,99),ByteRange.parse("bytes=90-200",100))
 }
 @Test fun rejectsInvalidRanges(){
  for(value in listOf("bytes=100-","bytes=20-10","bytes=-0","bytes=1-2,4-5","bytes=--1","bytes=x-","items=0-1")){
   assertThrows(IllegalArgumentException::class.java){ByteRange.parse(value,100)}
  }
  assertThrows(IllegalArgumentException::class.java){ByteRange.parse("bytes=0-",0)}
 }
 @Test fun serializedIdentityKeepsTheExactCertificateAndPrivateKey(){
  val original=DeviceIdentity.create()
  val restored=DeviceIdentity.parse(original.json())
  assertEquals(original.auth,restored.auth)
  assertArrayEquals(original.held.certificate.encoded,restored.held.certificate.encoded)
  assertArrayEquals(original.held.keyPair.private.encoded,restored.held.keyPair.private.encoded)
  assertTrue(restored.held.certificate.notAfter.time-System.currentTimeMillis()>365L*24*3600*1000)
 }
 @Test fun malformedPersistedAuthorizationIsRejected(){
  val json=DeviceIdentity.create().json().put("auth","broken")
  assertThrows(IllegalArgumentException::class.java){DeviceIdentity.parse(json)}
 }
 @Test fun plaintextMediaIsRestrictedToNumericPrivateLanAddresses(){
  for(host in listOf("192.168.1.2","10.0.0.5","172.16.0.1","172.31.255.1","127.0.0.1"))assertTrue(privateLanHost(host))
  for(host in listOf("8.8.8.8","172.32.0.1","192.168.1.999","192.168.1","localhost","example.com","192.168.1.2.evil"))assertFalse(privateLanHost(host))
 }
 @Test fun maximumPlaybackPrefersResolutionThenFrameRate(){
  val uhd60=PlaybackQuality(3840,2160,59.94f,40_000_000)
  val uhd30=PlaybackQuality(3840,2160,30f,50_000_000)
  val hd60=PlaybackQuality(1920,1080,60f,60_000_000)
  assertTrue(uhd60>uhd30);assertTrue(uhd30>hd60)
  assertEquals(uhd60,listOf(hd60,uhd30,uhd60).maxOrNull())
 }

 @Test fun nativeSelectionRejectsPublicOrMixedOriginsAndMalformedItems(){
  fun body(url:String):org.json.JSONObject {
   val file=org.json.JSONObject().put("baseUrl",url).put("nativePin","a".repeat(64))
   val items=org.json.JSONArray().put(org.json.JSONObject().put("type","VIDEO").put("mediaFile",file))
   return org.json.JSONObject().put("requestId","test").put("session","native:test").put("source","native").put("token","ephemeral").put("items",items)
  }
  validateSelection(body("http://192.168.1.2:1234/media/0"))
  validateSelection(body("https://192.168.1.2:1234/media/0"))
  for(url in listOf("http://8.8.8.8:1234/media/0","http://example.com:1234/media/0","ftp://192.168.1.2:1234/media/0","http://192.168.1.2:1234/other","http://192.168.1.2:1234/media/0?redirect=bad"))assertThrows(IllegalArgumentException::class.java){validateSelection(body(url))}
  val mixed=body("http://192.168.1.2:1234/media/0")
  mixed.getJSONArray("items").put(body("http://192.168.1.3:1234/media/1").getJSONArray("items").getJSONObject(0))
  assertThrows(IllegalArgumentException::class.java){validateSelection(mixed)}
 }

}

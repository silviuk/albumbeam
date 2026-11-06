package dev.personal.photostv

import org.junit.Assert.*
import org.junit.Test

class ManualGateTest{
 @Test fun generatesSixUnambiguousCharacters(){repeat(100){assertTrue(ManualGate().code.matches(Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}")))}}
 @Test fun acceptsCaseInsensitiveOnce(){val g=ManualGate();assertTrue(g.consume(g.code.lowercase()));assertFalse(g.consume(g.code))}
 @Test fun locksAfterFiveFailedAttempts(){val g=ManualGate();repeat(5){assertFalse(g.consume("!!!!!!"))};assertFalse(g.consume(g.code))}
 @Test fun fifthAttemptCanSucceed(){val g=ManualGate();repeat(4){assertFalse(g.consume("!!!!!!"))};assertTrue(g.consume(g.code))}
 @Test fun expiresAtFiveMinutes(){var now=0L;val g=ManualGate{now};now=300000;assertFalse(g.consume(g.code))}
 @Test fun verificationIsGroupedAndStable(){assertEquals("ABCD-EF01-2345-6789",verification("abcdef0123456789"+"a".repeat(48)))}
}

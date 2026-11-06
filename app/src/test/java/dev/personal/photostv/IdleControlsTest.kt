package dev.personal.photostv
import org.junit.Test
import org.junit.Assert.*
class IdleControlsTest{
 @Test fun hidesAtThreeSeconds(){var time=0L;val controls=IdleControls{time};time=2999;assertFalse(controls.shouldHide(0));time=3000;assertTrue(controls.shouldHide(0))}
 @Test fun inputRestartsIdlePeriod(){var time=0L;val controls=IdleControls{time};time=2500;val event=controls.touch();time=3000;assertFalse(controls.shouldHide(event));time=5500;assertTrue(controls.shouldHide(event))}
 @Test fun staleCallbackCannotHideNewlyRevealedControls(){var time=0L;val controls=IdleControls{time};time=4000;controls.touch();time=8000;assertFalse(controls.shouldHide(0))}
 @Test fun openPanelKeepsControlsVisible(){var time=0L;val controls=IdleControls{time};time=10000;assertFalse(controls.shouldHide(0,panelOpen=true))}
}

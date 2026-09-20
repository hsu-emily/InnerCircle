package com.emilyhsu.innercircle.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeTapTest {
    @Test fun acceptsATapOnTheRightSide() {
        assertEquals(TapRequest(0.85f, 0.5f), parseTapRequest("""{"type":"nativeTap","x":0.85,"y":0.5}"""))
    }

    @Test fun usageMessagesAreNotTaps() {
        assertNull(parseTapRequest("""{"type":"usage","posts":3}"""))
    }

    @Test fun refusesTapsOutsideTheNextArea() {
        assertNull(parseTapRequest("""{"type":"nativeTap","x":0.1,"y":0.5}"""))   // left side = previous story
        assertNull(parseTapRequest("""{"type":"nativeTap","x":0.85,"y":0.97}""")) // bottom: buttons, nav bar
        assertNull(parseTapRequest("""{"type":"nativeTap","x":0.85,"y":0.02}""")) // top: close button
        assertNull(parseTapRequest("""{"type":"nativeTap","x":2,"y":0.5}"""))
    }

    @Test fun refusesMalformedInput() {
        assertNull(parseTapRequest("not json"))
        assertNull(parseTapRequest("""{"type":"nativeTap"}"""))
        assertNull(parseTapRequest("""{"type":"nativeTap","x":"right","y":"middle"}"""))
    }
}

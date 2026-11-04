package com.nemuigames.nemuitv.alerts

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertKeysTest {
    private var capturing = true
    private val calls = mutableListOf<String>()
    private val keys = AlertKeys({ capturing }, { calls += "ok" }, { calls += "back" })

    private fun press(code: Int): Pair<Boolean, Boolean> = keys.onKey(code, down = true) to keys.onKey(code, down = false)

    @Test fun notCapturing_everythingPassesThrough() {
        capturing = false
        assertEquals(false to false, press(KeyEvent.KEYCODE_BACK))
        assertEquals(false to false, press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `capturing okAndBack consumedWhole actOnUp`() {
        assertTrue(keys.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true))
        assertEquals(emptyList<String>(), calls) // not on down
        assertTrue(keys.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false))
        assertEquals(true to true, press(KeyEvent.KEYCODE_ENTER))
        assertEquals(true to true, press(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(true to true, press(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf("ok", "ok", "ok", "back"), calls)
    }

    @Test fun capturing_otherKeysReachTheApp() {
        assertEquals(false to false, press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(false to false, press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `popupClosesMidPress upStillConsumed noAction`() {
        assertTrue(keys.onKey(KeyEvent.KEYCODE_BACK, down = true))
        capturing = false // auto-close between down and up
        assertTrue(keys.onKey(KeyEvent.KEYCODE_BACK, down = false)) // the app never sees a lone Back-up
        assertEquals(emptyList<String>(), calls)
        assertEquals(false to false, press(KeyEvent.KEYCODE_BACK)) // and the next press is the app's
    }

    @Test fun ringMidPress_upGoesToTheApp() {
        capturing = false
        assertFalse(keys.onKey(KeyEvent.KEYCODE_BACK, down = true))
        capturing = true
        assertFalse(keys.onKey(KeyEvent.KEYCODE_BACK, down = false)) // its down was the app's
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun repeatsAndCanceledUp() {
        assertTrue(keys.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true))
        assertTrue(keys.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true)) // repeat
        assertTrue(keys.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, canceled = true))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun capture_onlyWhenOverlayOn() {
        // Service gate: overlay window on + doorbell shows. Overlay add failed -> the app in front keeps OK/Back.
        assertTrue(AlertKeys.overlayCaptures(overlayOn = true, doorbellShows = true))
        assertFalse(AlertKeys.overlayCaptures(overlayOn = false, doorbellShows = true))
        assertFalse(AlertKeys.overlayCaptures(overlayOn = true, doorbellShows = false))
        // In-app fallback gate: the exact complement while a doorbell shows (never both, never neither).
        assertTrue(AlertKeys.inAppCaptures(overlayOn = false, doorbellShows = true))
        assertFalse(AlertKeys.inAppCaptures(overlayOn = true, doorbellShows = true))
        assertFalse(AlertKeys.inAppCaptures(overlayOn = false, doorbellShows = false))

        var overlayOn = false
        val service = AlertKeys({ AlertKeys.overlayCaptures(overlayOn, true) }, { calls += "ok" }, { calls += "back" })
        assertFalse(service.onKey(KeyEvent.KEYCODE_BACK, down = true)) // overlay off: Netflix gets Back
        assertFalse(service.onKey(KeyEvent.KEYCODE_BACK, down = false))
        overlayOn = true
        assertTrue(service.onKey(KeyEvent.KEYCODE_BACK, down = true))
        assertTrue(service.onKey(KeyEvent.KEYCODE_BACK, down = false))
        assertEquals(listOf("back"), calls)
    }

    @Test fun lostUp_doesNotStick() {
        assertTrue(keys.onKey(KeyEvent.KEYCODE_BACK, down = true)) // up never arrives
        capturing = false
        assertFalse(keys.onKey(KeyEvent.KEYCODE_BACK, down = true)) // next press is the app's, whole
        assertFalse(keys.onKey(KeyEvent.KEYCODE_BACK, down = false))
    }
}

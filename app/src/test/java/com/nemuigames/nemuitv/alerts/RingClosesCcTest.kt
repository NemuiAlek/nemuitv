package com.nemuigames.nemuitv.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingClosesCcTest {
    private fun d(ring: Long, expanded: Boolean = false) = DoorbellUi("camera.x", "Front door", expanded, ring)

    @Test fun firstRing_closes() = assertTrue(ringClosesCc(null, d(1)))
    @Test fun noDoorbell_doesNot() = assertFalse(ringClosesCc(null, null))
    @Test fun doorbellClosed_doesNot() = assertFalse(ringClosesCc(d(1), null))
    @Test fun sameRingExpanded_doesNot() = assertFalse(ringClosesCc(d(1), d(1, expanded = true)))
    @Test fun ringAgain_closes() = assertTrue(ringClosesCc(d(1), d(2)))
}

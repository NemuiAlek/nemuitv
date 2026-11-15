package com.nemuigames.nemuitv.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class TeslaPaneTest {
    @Test fun barFractionClampsAndHandlesNull() {
        assertEquals(0f, barFraction(null), 0.0001f)
        assertEquals(0.97f, barFraction(97), 0.0001f)
        assertEquals(1f, barFraction(140), 0.0001f)
        assertEquals(0f, barFraction(-5), 0.0001f)
    }
}

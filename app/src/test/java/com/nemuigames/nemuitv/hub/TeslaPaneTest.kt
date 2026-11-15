package com.nemuigames.nemuitv.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaPaneTest {
    @Test fun barFractionClampsAndHandlesNull() {
        assertEquals(0f, barFraction(null), 0.0001f)
        assertEquals(0.97f, barFraction(97), 0.0001f)
        assertEquals(1f, barFraction(140), 0.0001f)
        assertEquals(0f, barFraction(-5), 0.0001f)
    }

    private fun card(p: String, climateOn: Boolean?, copText: String?) = CarStats(
        prefix = p, name = p, color = "gray", percent = 50, percentText = "50%", awake = CarAwake.AWAKE,
        statusText = "", rangeText = "", odometerText = "", insideText = "", outsideText = "",
        climateOn = climateOn, copText = copText, tires = emptyMap(),
    )

    @Test fun firstControl_fanThenCopThenNextCar() {
        assertEquals("a" to CarControl.FAN, teslaFirstControl(listOf(card("a", false, "On"), card("b", true, "On"))))
        assertEquals("a" to CarControl.COP, teslaFirstControl(listOf(card("a", null, "Off"), card("b", true, null))))
        assertEquals("b" to CarControl.FAN, teslaFirstControl(listOf(card("a", null, null), card("b", true, null))))
        assertNull(teslaFirstControl(listOf(card("a", null, null))))
        assertNull(teslaFirstControl(emptyList()))
    }

    @Test fun turningText_namesTheDirection() {
        assertEquals("Turning on…", turningText(wasOn = false))
        assertEquals("Turning off…", turningText(wasOn = true))
    }
}

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

    private fun card(p: String, climate: ClimateLook?, copText: String?) = CarStats(
        prefix = p, name = p, color = "gray", percent = 50, percentText = "50%", awake = CarAwake.AWAKE,
        statusText = "", rangeText = "", odometerText = "", insideText = "", outsideText = "",
        climate = climate, copText = copText, tires = emptyMap(),
    )

    @Test fun firstControl_fanThenCopThenNextCar() {
        assertEquals("a" to CarControl.FAN, teslaFirstControl(listOf(card("a", ClimateLook.OFF, "On"), card("b", ClimateLook.ON, "On"))))
        assertEquals("a" to CarControl.COP, teslaFirstControl(listOf(card("a", null, "Off"), card("b", ClimateLook.ON, null))))
        assertEquals("b" to CarControl.FAN, teslaFirstControl(listOf(card("a", null, null), card("b", ClimateLook.UNKNOWN, null))))
        assertNull(teslaFirstControl(listOf(card("a", null, null))))
        assertNull(teslaFirstControl(emptyList()))
    }

    @Test fun turningText_namesTheDirection() {
        assertEquals("Turning on…", turningText(wasOn = false))
        assertEquals("Turning off…", turningText(wasOn = true))
    }
}

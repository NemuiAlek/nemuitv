package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetRulesTest {
    private fun light(state: String, brightness: Int? = null, modes: List<String>? = null) = EntityState(
        state,
        JsonObject(buildMap {
            if (brightness != null) put("brightness", JsonPrimitive(brightness))
            if (modes != null) put("supported_color_modes", JsonArray(modes.map { JsonPrimitive(it) }))
        }),
    )

    @Test fun brightness_255to100() = assertEquals(100, brightnessPercent(light("on", 255)))
    @Test fun brightness_128to50() = assertEquals(50, brightnessPercent(light("on", 128)))
    @Test fun brightness_minimumIsOne() = assertEquals(1, brightnessPercent(light("on", 1)))
    @Test fun brightness_offIsNull() {
        assertNull(brightnessPercent(light("off", 255)))
        assertNull(brightnessPercent(null))
        assertNull(brightnessPercent(light("on")))
    }

    @Test fun nextBrightness_clamps() {
        assertEquals(10, nextBrightness(10, false))
        assertEquals(100, nextBrightness(100, true))
        assertEquals(50, nextBrightness(40, true))
        assertEquals(30, nextBrightness(40, false))
    }

    @Test fun dimmable_onoffOnly_false() {
        assertFalse(isDimmable(light("on", modes = listOf("onoff"))))
        assertTrue(isDimmable(light("on", modes = listOf("brightness"))))
        assertTrue(isDimmable(light("on", modes = listOf("color_temp", "xy"))))
        assertFalse(isDimmable(light("on")))
        assertFalse(isDimmable(null))
    }
}

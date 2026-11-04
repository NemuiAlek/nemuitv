package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaTextTest {
    private fun st(state: String, unit: String? = null) = EntityState(
        state, JsonObject(if (unit == null) emptyMap() else mapOf("unit_of_measurement" to JsonPrimitive(unit))),
    )

    private val cfg = HubConfig(carColors = linkedMapOf("my_car" to "red", "second_car" to "blue"), lowTirePsi = 38.0)
    private val model = HubModel(emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), emptyList(),
        listOf("second_car", "my_car"))

    private fun full(p: String) = mapOf(
        "sensor.${p}_battery_level" to st("97", "%"),
        "sensor.${p}_charging" to st("charging"),
        "sensor.${p}_battery_range" to st("212.4", "mi"),
        "sensor.${p}_odometer" to st("12345.6", "mi"),
        "sensor.${p}_tire_pressure_front_left" to st("41.2", "psi"),
        "sensor.${p}_tire_pressure_front_right" to st("40", "psi"),
        "sensor.${p}_tire_pressure_rear_left" to st("42", "psi"),
        "sensor.${p}_tire_pressure_rear_right" to st("37", "psi"),
    )

    @Test fun carStats_fullValues() {
        val c = carStats("my_car", full("my_car"), cfg)
        assertEquals("My Car", c.name)
        assertEquals("red", c.color)
        assertEquals(97, c.percent)
        assertEquals("97%", c.percentText)
        assertEquals("⚡ Charging", c.chargingText)
        assertEquals("212 mi", c.rangeText)
        assertEquals("12,346 mi", c.odometerText)
        assertEquals(Tire("41", false), c.tires["front_left"])
        assertEquals(Tire("40", false), c.tires["front_right"])
        assertEquals(Tire("42", false), c.tires["rear_left"])
        assertEquals(Tire("37", true), c.tires["rear_right"])
    }

    @Test fun chargingStopped_notCharging() {
        val s = full("second_car") + ("sensor.second_car_charging" to st("stopped"))
        val c = carStats("second_car", s, cfg)
        assertEquals("Not charging", c.chargingText)
    }

    @Test fun unavailableSensors_showDash_noLow() {
        val s = mapOf(
            "sensor.second_car_battery_level" to st("unavailable"),
            "sensor.second_car_charging" to st("unavailable"),
            "sensor.second_car_battery_range" to st("unknown"),
            "sensor.second_car_tire_pressure_front_left" to st("unavailable", "psi"),
        )
        val c = carStats("second_car", s, cfg)
        assertEquals(null, c.percent)
        assertEquals("—", c.percentText)
        assertEquals("—", c.chargingText)
        assertEquals("—", c.rangeText)
        assertEquals("—", c.odometerText)
        assertEquals(4, c.tires.size)
        c.tires.values.forEach { assertEquals(Tire("—", false), it) }
    }

    private fun tireAt(psi: String) = carStats(
        "second_car", mapOf("sensor.second_car_tire_pressure_front_left" to st(psi, "psi")), cfg,
    ).tires["front_left"]

    @Test fun tire_37_6_shows38_notLow() = assertEquals(Tire("38", false), tireAt("37.6"))
    @Test fun tire_37_4_shows37_low() = assertEquals(Tire("37", true), tireAt("37.4"))

    @Test fun tireLow_onlyPsi() {
        assertFalse(tireLow(2.5, "bar", 38.0))
        assertTrue(tireLow(37.0, "psi", 38.0))
        assertFalse(tireLow(null, "psi", 38.0))
    }

    @Test fun tireLow_atThreshold_false() {
        assertFalse(tireLow(38.0, "psi", 38.0))
    }

    @Test fun cards_followCarColorsOrder() {
        val cards = teslaCards(model, full("second_car") + full("my_car"), cfg)
        assertEquals(listOf("my_car", "second_car"), cards.map { it.prefix })
    }
}

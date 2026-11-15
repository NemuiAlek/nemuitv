package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val NY = java.time.ZoneId.of("America/New_York")
    private val NOW = java.time.ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, NY).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, NY).toInstant().toEpochMilli()
    private fun stat(p: String, state: String, lc: Long? = null) =
        mapOf("binary_sensor.${p}_status" to EntityState(state, JsonObject(emptyMap()), lc))

    @Test fun `lastUpdateText today`() = assertEquals("6:24 AM", lastUpdateText(at(15, 6, 24), NOW, NY))
    @Test fun `lastUpdateText yesterday`() = assertEquals("Yesterday 6:24 PM", lastUpdateText(at(14, 18, 24), NOW, NY))
    @Test fun `lastUpdateText older`() = assertEquals("Nov 13, 6:24 AM", lastUpdateText(at(13, 6, 24), NOW, NY))

    @Test fun `asleep replaces charging line`() {
        val s = full("my_car") + stat("my_car", "off", at(13, 6, 24))
        val c = carStats("my_car", s, cfg, NOW, NY, null, null)
        assertEquals(CarAwake.ASLEEP, c.awake)
        assertEquals("💤 Asleep · last update Nov 13, 6:24 AM", c.statusText)
        assertFalse(c.statusText.contains("Charging"))
    }

    @Test fun `asleep without last change`() =
        assertEquals("💤 Asleep", carStats("my_car", full("my_car") + stat("my_car", "off"), cfg, NOW, NY, null, null).statusText)

    @Test fun `unreachable states`() {
        for (v in listOf("unavailable", "unknown")) {
            val c = carStats("my_car", full("my_car") + stat("my_car", v), cfg, NOW, NY, null, null)
            assertEquals(CarAwake.UNREACHABLE, c.awake)
            assertEquals("Car not reachable", c.statusText)
        }
    }

    @Test fun `awake and missing status keep the charging text`() {
        assertEquals("⚡ Charging", carStats("my_car", full("my_car") + stat("my_car", "on"), cfg, NOW, NY, null, null).statusText)
        assertEquals(CarAwake.AWAKE, carStats("my_car", full("my_car"), cfg, NOW, NY, null, null).awake)
    }

    @Test fun `temperatures round and dash`() {
        val s = full("my_car") + ("sensor.my_car_inside_temperature" to st("77.72", "°F")) +
            ("sensor.my_car_outside_temperature" to st("unavailable"))
        val c = carStats("my_car", s, cfg, NOW, NY, null, null)
        assertEquals("Inside 78°", c.insideText)
        assertEquals("Outside —", c.outsideText)
        assertEquals("Inside —", carStats("my_car", full("my_car"), cfg, NOW, NY, null, null).insideText)
    }

    @Test fun `climate and cop mapping`() {
        fun c(climate: String?, cop: String?, id: Boolean = true): CarStats {
            val s = full("my_car") + listOfNotNull(
                climate?.let { "climate.my_car_climate" to st(it) },
                cop?.let { "climate.my_car_cabin_overheat_protection" to st(it) },
            )
            return carStats("my_car", s, cfg, NOW, NY, if (id) "climate.my_car_climate" else null,
                if (id) "climate.my_car_cabin_overheat_protection" else null)
        }
        assertEquals(ClimateLook.ON, c("heat_cool", "cool").climate)
        assertEquals(ClimateLook.OFF, c("off", "off").climate)
        assertEquals("On", c("off", "cool").copText)
        assertEquals("No A/C", c("off", "fan_only").copText)
        assertEquals("Off", c("off", "off").copText)
        assertEquals("—", c("off", "weird").copText)
        assertEquals("—", c("off", "unavailable").copText)
        assertNull(c("off", "off", id = false).copText)
        assertNull(c("off", "off", id = false).climate)
    }

    @Test fun `climate look is unknown for an unknown, unavailable or missing state, never off`() {
        fun look(state: String?): ClimateLook? {
            val s = full("my_car") + listOfNotNull(state?.let { "climate.my_car_climate" to st(it) })
            return carStats("my_car", s, cfg, NOW, NY, "climate.my_car_climate", null).climate
        }
        assertEquals(ClimateLook.UNKNOWN, look("unknown"))
        assertEquals(ClimateLook.UNKNOWN, look("unavailable"))
        assertEquals(ClimateLook.UNKNOWN, look(null))
        assertEquals(ClimateLook.ON, look("heat_cool"))
        assertEquals(ClimateLook.OFF, look("off"))
    }

    @Test fun carStats_fullValues() {
        val c = carStats("my_car", full("my_car"), cfg, NOW, NY, null, null)
        assertEquals("My Car", c.name)
        assertEquals("red", c.color)
        assertEquals(97, c.percent)
        assertEquals("97%", c.percentText)
        assertEquals("⚡ Charging", c.statusText)
        assertEquals("212 mi", c.rangeText)
        assertEquals("12,346 mi", c.odometerText)
        assertEquals(Tire("41", false), c.tires["front_left"])
        assertEquals(Tire("40", false), c.tires["front_right"])
        assertEquals(Tire("42", false), c.tires["rear_left"])
        assertEquals(Tire("37", true), c.tires["rear_right"])
    }

    @Test fun chargingStopped_notCharging() {
        val s = full("second_car") + ("sensor.second_car_charging" to st("stopped"))
        val c = carStats("second_car", s, cfg, NOW, NY, null, null)
        assertEquals("Not charging", c.statusText)
    }

    @Test fun unavailableSensors_showDash_noLow() {
        val s = mapOf(
            "sensor.second_car_battery_level" to st("unavailable"),
            "sensor.second_car_charging" to st("unavailable"),
            "sensor.second_car_battery_range" to st("unknown"),
            "sensor.second_car_tire_pressure_front_left" to st("unavailable", "psi"),
        )
        val c = carStats("second_car", s, cfg, NOW, NY, null, null)
        assertEquals(null, c.percent)
        assertEquals("—", c.percentText)
        assertEquals("—", c.statusText)
        assertEquals("—", c.rangeText)
        assertEquals("—", c.odometerText)
        assertEquals(4, c.tires.size)
        c.tires.values.forEach { assertEquals(Tire("—", false), it) }
    }

    private fun tireAt(psi: String) = carStats(
        "second_car", mapOf("sensor.second_car_tire_pressure_front_left" to st(psi, "psi")), cfg, NOW, NY, null, null,
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
        val cards = teslaCards(model, full("second_car") + full("my_car"), cfg, NOW, NY)
        assertEquals(listOf("my_car", "second_car"), cards.map { it.prefix })
    }

    @Test fun `cards useModelClimateAndCopIds`() {
        val m = model.copy(
            carClimate = mapOf("my_car" to "climate.my_car_climate"),
            carCop = mapOf("my_car" to "climate.my_car_cabin_overheat_protection"),
        )
        val states = full("second_car") + full("my_car") + mapOf(
            "climate.my_car_climate" to st("heat_cool"), "climate.my_car_cabin_overheat_protection" to st("fan_only"),
        )
        val (mine, second) = teslaCards(m, states, cfg, NOW, NY)
        assertEquals(ClimateLook.ON, mine.climate)
        assertEquals("No A/C", mine.copText)
        assertNull(second.climate)
        assertNull(second.copText)
    }
}

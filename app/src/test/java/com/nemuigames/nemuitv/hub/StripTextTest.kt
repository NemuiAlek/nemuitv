package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripTextTest {
    private fun st(state: String, vararg attrs: Pair<String, Any>) = EntityState(
        state,
        JsonObject(attrs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                else -> JsonPrimitive(v.toString())
            }
        }),
    )

    private val model = HubModel(
        rooms = listOf(
            Room("lr", "Living Room", listOf(
                Tile("light.a", "A", "light", false),
                Tile("light.b", "B", "light", false),
                Tile("switch.c", "C", "switch", false),
                Tile("light.keep", "Keep", "light", true),
                Tile("fan.f", "F", "fan", false),
                Tile("light.off", "Off", "light", false),
            )),
        ),
        scenes = emptyList(), cameras = emptyList(), thermostat = "climate.home",
        locks = listOf("lock.front_door", "lock.back_door"),
        lockBatteries = listOf("sensor.front_door_battery", "sensor.front_door_other"),
        people = listOf("person.alex", "person.sam"),
        cars = listOf("my_car", "second_car"),
    )
    private val cfg = HubConfig(
        peopleNames = mapOf("person.alex" to "Alex"),
        carColors = mapOf("my_car" to "darkgray"),
    )
    private val base = mapOf(
        "lock.front_door" to st("locked", "friendly_name" to "Front door"),
        "lock.back_door" to st("locked", "friendly_name" to "Back door"),
        "sensor.front_door_battery" to st("80", "device_class" to "battery"),
        "sensor.front_door_other" to st("5", "device_class" to "temperature"),
        "climate.home" to st("heat", "current_temperature" to 72.4),
        "light.a" to st("on"), "light.b" to st("on"), "switch.c" to st("on"),
        "light.keep" to st("on"), "fan.f" to st("on"), "light.off" to st("off"),
        "person.alex" to st("home", "friendly_name" to "AlexHome"),
        "person.sam" to st("home", "friendly_name" to "Sam"),
        "sensor.my_car_battery_level" to st("97"),
        "sensor.my_car_charging" to st("charging"),
        "sensor.second_car_battery_level" to st("86"),
        "sensor.second_car_charging" to st("disconnected"),
    )

    @Test fun lock_allLocked() {
        val s = buildStrip(model, base, cfg)
        assertEquals("Locked", s.lock)
        assertEquals(LockStatus.LOCKED, s.lockStatus)
    }

    @Test fun lock_oneUnlocked() {
        val s = buildStrip(model, base + ("lock.front_door" to st("unlocked", "friendly_name" to "Front door")), cfg)
        assertEquals("Front door", s.lock)
        assertEquals(LockStatus.UNLOCKED, s.lockStatus)
    }

    @Test fun lock_jammed() {
        val s = buildStrip(model, base + ("lock.back_door" to st("jammed")) + ("lock.front_door" to st("unlocked")), cfg)
        assertEquals(LockStatus.JAMMED, s.lockStatus)
        assertEquals("Jammed", s.lock)
    }

    @Test fun lock_unavailable() {
        val s = buildStrip(model, base + ("lock.back_door" to st("unavailable")), cfg)
        assertEquals(LockStatus.UNAVAILABLE, s.lockStatus)
        assertEquals("Lock unavailable", s.lock)
    }

    @Test fun lock_missingState() {
        val s = buildStrip(model, base - "lock.back_door", cfg)
        assertEquals(LockStatus.UNAVAILABLE, s.lockStatus)
    }

    @Test fun lock_unlocking_isUnlocked() {
        val s = buildStrip(model, base + ("lock.front_door" to st("unlocking", "friendly_name" to "Front door")), cfg)
        assertEquals(LockStatus.UNLOCKED, s.lockStatus)
        assertEquals("Front door", s.lock)
    }

    @Test fun lock_noLocks_none() {
        val s = buildStrip(model.copy(locks = emptyList()), base, cfg)
        assertEquals(LockStatus.NONE, s.lockStatus)
        assertEquals("", s.lock)
    }

    @Test fun lock_unlockedFallsBackToObjectId() {
        val s = buildStrip(model, base + ("lock.back_door" to st("unlocked")), cfg)
        assertEquals("back door", s.lock)
    }

    @Test fun lock_batteryUnder25_warns() {
        assertTrue(buildStrip(model, base + ("sensor.front_door_battery" to st("24", "device_class" to "battery")), cfg).lockWarning)
        assertFalse(buildStrip(model, base + ("sensor.front_door_battery" to st("25", "device_class" to "battery")), cfg).lockWarning)
        assertFalse(buildStrip(model, base + ("sensor.front_door_battery" to st("unavailable", "device_class" to "battery")), cfg).lockWarning)
    }

    @Test fun lock_nonBatteryCandidateIgnored() {
        assertFalse(buildStrip(model, base, cfg).lockWarning)
    }

    @Test fun insideTemp_fromThermostatCurrent() {
        assertEquals("72°", buildStrip(model, base, cfg).insideTemp)
        assertNull(buildStrip(model.copy(thermostat = null), base, cfg).insideTemp)
        assertNull(buildStrip(model, base + ("climate.home" to st("heat")), cfg).insideTemp)
    }

    @Test fun lightsOn_skipsKeepOn() {
        assertEquals(3, lightsOnCount(model.rooms, base))
        assertEquals(3, buildStrip(model, base, cfg).lightsOn)
    }

    @Test fun people_homeOnly_withOverrides() {
        assertEquals("Alex, Sam", buildStrip(model, base, cfg).people)
        assertEquals("Alex", buildStrip(model, base + ("person.sam" to st("not_home", "friendly_name" to "Sam")), cfg).people)
        assertEquals("AlexHome, Sam", buildStrip(model, base, HubConfig()).people)
    }

    @Test fun people_nobodyHome() {
        val s = base + ("person.alex" to st("not_home")) + ("person.sam" to st("not_home"))
        assertEquals("Nobody home", buildStrip(model, s, cfg).people)
    }

    @Test fun cars_percentAndCharging() {
        val cars = buildStrip(model, base, cfg).cars
        assertEquals(CarChip("my_car", "My Car", 97, true, "darkgray"), cars[0])
        assertEquals(CarChip("second_car", "Second Car", 86, false, "gray"), cars[1])
    }

    @Test fun cars_noColorEntry_defaultsToGray() {
        val cars = buildStrip(model, base, HubConfig()).cars
        assertTrue(cars.isNotEmpty())
        assertTrue(cars.all { it.color == "gray" })
    }

    @Test fun cars_orderFollowsCarColorsKeys() {
        val m = model.copy(cars = listOf("second_car", "zeta", "bravo", "my_car"))
        val c = HubConfig(carColors = linkedMapOf("my_car" to "darkgray", "second_car" to "white"))
        assertEquals(listOf("my_car", "second_car", "bravo", "zeta"), buildStrip(m, base, c).cars.map { it.prefix })
    }

    @Test fun cars_asleepMissing() {
        val s = base - "sensor.second_car_battery_level" + ("sensor.my_car_battery_level" to st("unavailable"))
        val cars = buildStrip(model, s, cfg).cars
        assertNull(cars[0].percent)
        assertNull(cars[1].percent)
    }

    @Test fun orderedCars_configOrderThenName() {
        val c = HubConfig(carColors = linkedMapOf("my_car" to "darkgray", "second_car" to "white"))
        assertEquals(
            listOf("my_car", "second_car", "bravo", "zeta"),
            orderedCars(listOf("second_car", "zeta", "bravo", "my_car"), c),
        )
    }

    @Test fun carDisplayName_titleCasesWords() {
        assertEquals("My Car", carDisplayName("my_car"))
        assertEquals("Second Car", carDisplayName("second_car"))
    }
}

package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.DEFAULT_CAR_COLOR
import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

data class CarChip(val prefix: String, val name: String, val percent: Int?, val charging: Boolean, val color: String,
    val asleep: Boolean = false,
)
enum class LockStatus { LOCKED, UNLOCKED, JAMMED, UNAVAILABLE, NONE }

data class Strip(
    val lock: String, val lockStatus: LockStatus, val lockWarning: Boolean, val insideTemp: String?, val lightsOn: Int,
    val people: String, val cars: List<CarChip>,
)

private const val LOW_BATTERY = 25

private fun attr(s: EntityState?, key: String): JsonPrimitive? = s?.attributes?.get(key) as? JsonPrimitive
private fun objectId(entityId: String) = entityId.substringAfter('.').replace('_', ' ')
private fun friendlyName(id: String, s: EntityState?) =
    attr(s, "friendly_name")?.contentOrNull ?: objectId(id)

fun lightsOnCount(rooms: List<Room>, states: Map<String, EntityState>): Int =
    rooms.sumOf { r ->
        r.tiles.count { !it.keepOn && (it.domain == "light" || it.domain == "switch") && isOn(states[it.entityId]) }
    }

val TIRE_POSITIONS = listOf("front_left", "front_right", "rear_left", "rear_right")

/** "my_car" -> "My Car". */
fun carDisplayName(prefix: String): String =
    prefix.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** Order: the key order of hub.carColors in config.json (my_car, second_car), then any other car by name. */
fun orderedCars(cars: List<String>, cfg: HubConfig): List<String> {
    val colorOrder = cfg.carColors.keys.toList()
    return cars.sortedWith(
        compareBy<String>({ p -> colorOrder.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it } }, { it }),
    )
}

fun buildStrip(model: HubModel, states: Map<String, EntityState>, cfg: HubConfig): Strip {
    val notSecure = setOf("unlocked", "unlocking", "open", "opening", "locking")
    val (lockStatus, lock) = when {
        model.locks.isEmpty() -> LockStatus.NONE to ""
        model.locks.any { states[it]?.state == "jammed" } -> LockStatus.JAMMED to "Jammed"
        model.locks.any { states[it]?.state in notSecure } ->
            LockStatus.UNLOCKED to model.locks.filter { states[it]?.state in notSecure }
                .joinToString(", ") { friendlyName(it, states[it]) }
        model.locks.any { states[it]?.state != "locked" } -> LockStatus.UNAVAILABLE to "Lock unavailable"
        else -> LockStatus.LOCKED to "Locked"
    }

    val lockWarning = model.lockBatteries.any { id ->
        val s = states[id]
        attr(s, "device_class")?.contentOrNull == "battery" &&
            (s!!.state.toDoubleOrNull()?.let { it < LOW_BATTERY } ?: false)
    }

    val insideTemp = model.thermostat
        ?.let { attr(states[it], "current_temperature")?.doubleOrNull }
        ?.let { "${it.roundToInt()}°" }

    val home = model.people.filter { states[it]?.state == "home" }
        .map { cfg.peopleNames[it] ?: friendlyName(it, states[it]) }
    val people = if (home.isEmpty()) "Nobody home" else home.joinToString(", ")

    val cars = orderedCars(model.cars, cfg).map { p ->
        val awake = carAwake(states["binary_sensor.${p}_status"])
        CarChip(
            prefix = p,
            name = carDisplayName(p),
            percent = states["sensor.${p}_battery_level"]?.state?.toDoubleOrNull()?.roundToInt(),
            charging = awake == CarAwake.AWAKE && states["sensor.${p}_charging"]?.state == "charging",
            color = cfg.carColors[p] ?: DEFAULT_CAR_COLOR,
            asleep = awake != CarAwake.AWAKE,
        )
    }
    return Strip(lock, lockStatus, lockWarning, insideTemp, lightsOnCount(model.rooms, states), people, cars)
}

package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.DEFAULT_CAR_COLOR
import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val DASH = "—"

data class Tire(val text: String, val low: Boolean)
enum class CarAwake { AWAKE, ASLEEP, UNREACHABLE }

/** What the car's fan button shows: on (`heat_cool`), off, or unknown (no / unknown / unavailable state). */
enum class ClimateLook { ON, OFF, UNKNOWN }

/** `heat_cool` = on; unknown, unavailable or missing = [ClimateLook.UNKNOWN] (never shown as off); else off. */
fun climateLook(s: EntityState?): ClimateLook = when {
    isUnavailable(s) -> ClimateLook.UNKNOWN
    s!!.state == "heat_cool" -> ClimateLook.ON
    else -> ClimateLook.OFF
}

data class CarStats(
    val prefix: String, val name: String, val color: String, val percent: Int?, val percentText: String,
    val awake: CarAwake, val statusText: String, val rangeText: String, val odometerText: String,
    val insideText: String, val outsideText: String, /** Null = the car has no climate entity (no fan button). */
    val climate: ClimateLook?, val copText: String?,
    val tires: Map<String, Tire>,
)

private val TimeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DateTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** When the car's data last changed: `6:24 AM` today, `Yesterday 6:24 PM`, else `Nov 13, 6:24 AM`. */
fun lastUpdateText(atMs: Long, nowMs: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (at.toLocalDate()) {
        today -> TimeFmt.format(at)
        today.minusDays(1) -> "Yesterday ${TimeFmt.format(at)}"
        else -> DateTimeFmt.format(at)
    }
}

fun carAwake(status: EntityState?): CarAwake = when {
    status == null -> CarAwake.AWAKE
    status.state == "on" -> CarAwake.AWAKE
    status.state == "off" -> CarAwake.ASLEEP
    status.state == "unavailable" || status.state == "unknown" -> CarAwake.UNREACHABLE
    else -> CarAwake.AWAKE
}

private fun temperature(s: EntityState?, label: String): String =
    number(s)?.let { "$label ${it.roundToInt()}°" } ?: "$label $DASH"

private fun copLabel(s: EntityState): String = when (s.state) {
    "cool" -> "On"
    "fan_only" -> "No A/C"
    "off" -> "Off"
    else -> DASH
}

/** Low only when the sensor reports psi; other units are never judged against the psi threshold. */
fun tireLow(psi: Double?, unit: String?, threshold: Double): Boolean =
    psi != null && unit == "psi" && psi < threshold

private fun number(s: EntityState?): Double? = if (isUnavailable(s)) null else s!!.state.toDoubleOrNull()
private fun unit(s: EntityState?): String? = (s?.attributes?.get("unit_of_measurement") as? JsonPrimitive)?.contentOrNull

private fun withUnit(s: EntityState?, grouped: Boolean): String {
    val n = number(s) ?: return DASH
    val v = n.roundToInt()
    val text = if (grouped) String.format(Locale.US, "%,d", v) else v.toString()
    return unit(s)?.let { "$text $it" } ?: text
}

fun carStats(
    prefix: String, states: Map<String, EntityState>, cfg: HubConfig,
    nowMs: Long, zone: ZoneId, climateId: String?, copId: String?,
): CarStats {
    val statusState = states["binary_sensor.${prefix}_status"]
    val awake = carAwake(statusState)
    val level = states["sensor.${prefix}_battery_level"]
    val charging = states["sensor.${prefix}_charging"]
    val percent = number(level)?.roundToInt()
    val tires = TIRE_POSITIONS.associateWith { pos ->
        val s = states["sensor.${prefix}_tire_pressure_$pos"]
        val psi = number(s)
        // Judged on the shown (rounded) number, so an orange tire never displays a value at/above the threshold.
        val shown = psi?.roundToInt()
        Tire(shown?.toString() ?: DASH, tireLow(shown?.toDouble(), unit(s), cfg.lowTirePsi))
    }
    return CarStats(
        prefix = prefix,
        name = carDisplayName(prefix),
        color = cfg.carColors[prefix] ?: DEFAULT_CAR_COLOR,
        percent = percent,
        percentText = percent?.let { "$it%" } ?: DASH,
        awake = awake,
        statusText = when (awake) {
            CarAwake.AWAKE -> when {
                isUnavailable(charging) -> DASH
                charging!!.state == "charging" -> "⚡ Charging"
                else -> "Not charging"
            }
            CarAwake.ASLEEP -> statusState?.lastChangedMs
                ?.let { "💤 Asleep · last update ${lastUpdateText(it, nowMs, zone)}" } ?: "💤 Asleep"
            CarAwake.UNREACHABLE -> "Car not reachable"
        },
        insideText = temperature(states["sensor.${prefix}_inside_temperature"], "Inside"),
        outsideText = temperature(states["sensor.${prefix}_outside_temperature"], "Outside"),
        climate = climateId?.let { climateLook(states[it]) },
        copText = copId?.let { id -> states[id]?.let(::copLabel) ?: DASH },
        rangeText = withUnit(states["sensor.${prefix}_battery_range"], grouped = false),
        odometerText = withUnit(states["sensor.${prefix}_odometer"], grouped = true),
        tires = tires,
    )
}

fun teslaCards(model: HubModel, states: Map<String, EntityState>, cfg: HubConfig, nowMs: Long, zone: ZoneId): List<CarStats> =
    orderedCars(model.cars, cfg).map { carStats(it, states, cfg, nowMs, zone, model.carClimate[it], model.carCop[it]) }

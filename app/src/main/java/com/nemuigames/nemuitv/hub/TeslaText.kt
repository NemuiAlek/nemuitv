package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.DEFAULT_CAR_COLOR
import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import kotlin.math.roundToInt

private const val DASH = "—"

data class Tire(val text: String, val low: Boolean)
data class CarStats(
    val prefix: String, val name: String, val color: String, val percent: Int?, val percentText: String,
    val chargingText: String, val rangeText: String, val odometerText: String, val tires: Map<String, Tire>,
)

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

fun carStats(prefix: String, states: Map<String, EntityState>, cfg: HubConfig): CarStats {
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
        chargingText = when {
            isUnavailable(charging) -> DASH
            charging!!.state == "charging" -> "⚡ Charging"
            else -> "Not charging"
        },
        rangeText = withUnit(states["sensor.${prefix}_battery_range"], grouped = false),
        odometerText = withUnit(states["sensor.${prefix}_odometer"], grouped = true),
        tires = tires,
    )
}

fun teslaCards(model: HubModel, states: Map<String, EntityState>, cfg: HubConfig): List<CarStats> =
    orderedCars(model.cars, cfg).map { carStats(it, states, cfg) }

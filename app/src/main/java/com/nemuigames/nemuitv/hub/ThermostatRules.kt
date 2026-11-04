package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ThermoMode(val ha: String, val label: String, val icon: String) {
    COOL("cool", "Cool", "❄"), HEAT("heat", "Heat", "🔥"), AUTO("heat_cool", "Auto", "⇅"), OFF("off", "Off", "⏻")
}

data class ThermoDraft(
    val modes: List<ThermoMode>, val mode: ThermoMode, val target: Int?, val low: Int?, val high: Int?,
    val current: Int?, val min: Int, val max: Int,
)

/** One HA service call in the `climate` domain. */
data class ClimateCall(val service: String, val data: JsonObject)

private const val DEFAULT_MIN = 50
private const val DEFAULT_MAX = 90
private const val DEFAULT_TARGET = 72

private fun EntityState.num(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull
private fun EntityState.whole(key: String): Int? = num(key)?.roundToInt()
private fun modeOf(ha: String): ThermoMode? = ThermoMode.entries.firstOrNull { it.ha == ha }

/** `hvac_modes` intersected with the enum, in enum order (Cool, Heat, Auto, Off). */
fun thermoModes(state: EntityState?): List<ThermoMode> {
    val offered = (state?.attributes?.get("hvac_modes") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return emptyList()
    return ThermoMode.entries.filter { it.ha in offered }
}

fun startDraft(state: EntityState?): ThermoDraft? {
    if (state == null || isUnavailable(state)) return null
    val current = state.whole("current_temperature")
    return ThermoDraft(
        modes = thermoModes(state),
        mode = modeOf(state.state) ?: ThermoMode.OFF,
        target = state.whole("temperature") ?: current ?: DEFAULT_TARGET,
        low = state.whole("target_temp_low"),
        high = state.whole("target_temp_high"),
        current = current,
        min = state.whole("min_temp") ?: DEFAULT_MIN,
        max = state.whole("max_temp") ?: DEFAULT_MAX,
    )
}

fun nudgeTemp(d: ThermoDraft, up: Boolean): ThermoDraft {
    val step = if (up) 1 else -1
    return when (d.mode) {
        ThermoMode.COOL, ThermoMode.HEAT ->
            d.target?.let { d.copy(target = (it + step).coerceIn(d.min, d.max)) } ?: d
        ThermoMode.AUTO -> {
            val lo = d.low ?: return d
            val hi = d.high ?: return d
            if (lo + step < d.min || hi + step > d.max || lo + step > d.max || hi + step < d.min) d
            else d.copy(low = lo + step, high = hi + step)
        }
        ThermoMode.OFF -> d
    }
}

fun moveMode(d: ThermoDraft, right: Boolean): ThermoDraft {
    if (d.modes.isEmpty()) return d
    val i = d.modes.indexOf(d.mode)
    val next = when {
        i < 0 -> if (right) d.modes.first() else d.modes.last()
        else -> d.modes[(i + if (right) 1 else -1).coerceIn(0, d.modes.lastIndex)]
    }
    if (next == d.mode) return d
    return when (next) {
        ThermoMode.AUTO -> {
            val t = d.target ?: d.current ?: DEFAULT_TARGET
            d.copy(
                mode = next,
                low = d.low ?: (t - 3).coerceIn(d.min, d.max),
                high = d.high ?: (t + 3).coerceIn(d.min, d.max),
            )
        }
        ThermoMode.COOL, ThermoMode.HEAT -> {
            val mid = if (d.low != null && d.high != null) (d.low + d.high) / 2 else null
            d.copy(mode = next, target = d.target ?: mid ?: d.current ?: DEFAULT_TARGET)
        }
        ThermoMode.OFF -> d.copy(mode = next)
    }
}

private fun call(service: String, vararg fields: Pair<String, Any>) = ClimateCall(
    service,
    JsonObject(fields.associate { (k, v) -> k to (if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString())) }),
)

/** The single HA call that takes [start] to [end]; null when nothing changed. */
fun climateCall(start: ThermoDraft, end: ThermoDraft): ClimateCall? = when (end.mode) {
    ThermoMode.OFF ->
        if (start.mode == ThermoMode.OFF) null else call("set_hvac_mode", "hvac_mode" to ThermoMode.OFF.ha)
    ThermoMode.COOL, ThermoMode.HEAT -> when {
        end.target == null ->
            if (start.mode == end.mode) null else call("set_hvac_mode", "hvac_mode" to end.mode.ha)
        start.mode == end.mode && start.target == end.target -> null
        else -> call("set_temperature", "hvac_mode" to end.mode.ha, "temperature" to end.target)
    }
    ThermoMode.AUTO -> when {
        end.low == null || end.high == null ->
            if (start.mode == end.mode) null else call("set_hvac_mode", "hvac_mode" to end.mode.ha)
        start.mode == end.mode && start.low == end.low && start.high == end.high -> null
        else -> call(
            "set_temperature", "hvac_mode" to end.mode.ha,
            "target_temp_low" to end.low, "target_temp_high" to end.high,
        )
    }
}

/** HA reports the draft's mode and temperature(s), within half a degree. */
fun climateConfirmed(end: ThermoDraft, state: EntityState?): Boolean {
    if (state == null || isUnavailable(state) || state.state != end.mode.ha) return false
    fun near(key: String, want: Int?) = want == null || (state.num(key)?.let { abs(it - want) <= 0.5 } ?: false)
    return when (end.mode) {
        ThermoMode.COOL, ThermoMode.HEAT -> near("temperature", end.target)
        ThermoMode.AUTO -> near("target_temp_low", end.low) && near("target_temp_high", end.high)
        ThermoMode.OFF -> true
    }
}

fun thermostatTileText(state: EntityState?): String {
    if (state == null || isUnavailable(state)) return "Thermostat unavailable"
    val cur = state.whole("current_temperature")?.let { "$it°" }
    val mode = modeOf(state.state)
        ?: return listOfNotNull(
            cur, state.state.replace('_', ' ').replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
    val target = when (mode) {
        ThermoMode.COOL, ThermoMode.HEAT -> state.whole("temperature")?.let { "$it°" }
        ThermoMode.AUTO -> {
            val lo = state.whole("target_temp_low"); val hi = state.whole("target_temp_high")
            if (lo != null && hi != null) "$lo–$hi°" else null
        }
        ThermoMode.OFF -> null
    }
    val temps = listOfNotNull(cur, target).joinToString(" → ")
    return listOfNotNull(mode.icon + (if (temps.isEmpty()) "" else " $temps"), mode.label).joinToString(" · ")
}

/** The thermostat sheet's title: `Thermostat · now 72° inside` (just `Thermostat` without a current reading). */
fun thermoSheetTitle(d: ThermoDraft): String = d.current?.let { "Thermostat · now $it° inside" } ?: "Thermostat"

/** The sheet's big number: `70°`, Auto `70–76°`, Off `Off`; `—` when HA gave no value. */
fun thermoTargetText(d: ThermoDraft): String = when (d.mode) {
    ThermoMode.OFF -> "Off"
    ThermoMode.AUTO -> if (d.low != null && d.high != null) "${d.low}–${d.high}°" else "—"
    ThermoMode.COOL, ThermoMode.HEAT -> d.target?.let { "$it°" } ?: "—"
}

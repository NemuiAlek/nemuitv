package com.nemuigames.nemuitv.hub

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An appliance cycle (washer, dryer, dishwasher…): a room tile whose device also has a `*_remaining_time` sensor.
 * [total] (`*_total_time`) gives the bar, [status] (`*_current_status`) says whether it really runs.
 * Found by suffix on the tile's own device, so it works for any brand whose integration names them this way.
 */
data class Cycle(val tileId: String, val name: String, val remaining: String, val total: String?, val status: String?)

/** A running cycle: whole minutes left (rounded up) and how far along it is (null = no reliable total). */
data class CycleProgress(val minutesLeft: Int, val fraction: Float?)

/** Status values that mean "not running" (LG ThinQ's plus common ones); anything else counts as running. */
private val IDLE_STATUS = setOf(
    "end", "power_off", "off", "initial", "sleep", "idle", "standby", "complete", "completed", "finished",
    "unavailable", "unknown", "",
)

private fun EntityState.unit(): String? = (attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull

/** Minutes in a duration state: number in its unit (min default; h, s). */
private fun minutesOf(s: EntityState): Double? {
    val n = s.state.toDoubleOrNull() ?: return null
    return when (s.unit()?.lowercase()) {
        "h" -> n * 60
        "s" -> n / 60
        else -> n
    }
}

/**
 * The cycle's progress at [nowMs], or null when it isn't running: an idle [status], no/unknown/garbage [remaining],
 * or nothing left. [remaining] is either an end time (ISO timestamp, LG) or a duration (number + unit).
 */
fun cycleProgress(status: EntityState?, remaining: EntityState?, total: EntityState?, nowMs: Long): CycleProgress? {
    if (status != null && status.state.lowercase() in IDLE_STATUS) return null
    remaining ?: return null
    val leftMs: Double = runCatching { OffsetDateTime.parse(remaining.state).toInstant().toEpochMilli() - nowMs.toDouble() }
        .getOrNull() ?: (minutesOf(remaining)?.times(60_000) ?: return null)
    if (leftMs <= 0) return null
    val left = kotlin.math.ceil(leftMs / 60_000).toInt()
    val totalMin = total?.let { minutesOf(it) }?.takeIf { it > 0 }
    val fraction = totalMin?.takeIf { leftMs / 60_000 <= it }?.let { (1 - leftMs / 60_000 / it).toFloat().coerceIn(0f, 1f) }
    return CycleProgress(left, fraction)
}

/** "32 min left", "1 h 5 min left", "2 h left". */
fun cycleLeftText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min left"
        m == 0 -> "$h h left"
        else -> "$h h $m min left"
    }
}

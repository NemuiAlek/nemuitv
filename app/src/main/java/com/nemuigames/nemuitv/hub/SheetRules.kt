package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** HA brightness 0..255 -> percent; null if off or not reporting brightness. */
fun brightnessPercent(state: EntityState?): Int? {
    if (!isOn(state)) return null
    val b = (state!!.attributes["brightness"] as? JsonPrimitive)?.doubleOrNull ?: return null
    return (b * 100 / 255).roundToInt().coerceIn(1, 100)
}

/** Dimmable when supported_color_modes has anything besides "onoff". */
fun isDimmable(state: EntityState?): Boolean {
    val modes = state?.attributes?.get("supported_color_modes") as? JsonArray ?: return false
    return modes.any { (it as? JsonPrimitive)?.contentOrNull.let { m -> m != null && m != "onoff" } }
}

fun nextBrightness(current: Int, up: Boolean): Int =
    (current + if (up) 10 else -10).coerceIn(10, 100)

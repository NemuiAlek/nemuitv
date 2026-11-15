package com.nemuigames.nemuitv.hub

internal const val OVERVIEW = "overview"
internal const val TESLA = "tesla"

/** The hub rail's selectable keys, in order: Overview, Tesla (only when HA has a car), then the rooms. */
internal fun railKeys(roomKeys: List<String>, hasCars: Boolean): List<String> =
    listOf(OVERVIEW) + (if (hasCars) listOf(TESLA) else emptyList()) + roomKeys

/** LazyColumn index of rail key #[keyIndex]; the divider sits after the top group (1 item, or 2 with a car). */
internal fun railIndex(keyIndex: Int, hasCars: Boolean): Int {
    val top = if (hasCars) 2 else 1
    return if (keyIndex >= top) keyIndex + 1 else keyIndex
}

/** [selected] if it is still on the rail, else Overview (e.g. the car disappeared). */
internal fun validSelection(selected: String, keys: List<String>): String =
    if (selected in keys) selected else OVERVIEW

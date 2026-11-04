package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SceneUi(val entityId: String, val icon: String, val name: String, val flagOn: Boolean, val pending: Boolean)

/** A scene button's icon, from keywords in the script's object id (e.g. `script.night_mode` → 💤). */
fun sceneIcon(scriptId: String): String {
    val id = scriptId.substringAfter('.').lowercase()
    return when {
        "night" in id -> "💤"
        "guest" in id -> "👥"
        "movie" in id -> "🎬"
        "off" in id -> "⬛"
        else -> "▶"
    }
}

/** friendly_name, else the object id with "_" -> " " and the first letter capitalised. */
fun entityName(entityId: String, state: EntityState?): String =
    (state?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull
        ?: entityId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

fun sceneName(scriptId: String, state: EntityState?): String = entityName(scriptId, state)

enum class LockTileStatus { LOCKED, UNLOCKED, LOCKING, JAMMED, UNAVAILABLE }

fun lockTileStatus(state: EntityState?): LockTileStatus {
    if (isUnavailable(state)) return LockTileStatus.UNAVAILABLE
    return when (state!!.state) {
        "locked" -> LockTileStatus.LOCKED
        "locking" -> LockTileStatus.LOCKING
        "unlocked", "unlocking", "open", "opening" -> LockTileStatus.UNLOCKED
        "jammed" -> LockTileStatus.JAMMED
        else -> LockTileStatus.UNAVAILABLE
    }
}

fun lockTileText(name: String, status: LockTileStatus): String = when (status) {
    LockTileStatus.LOCKED -> "🔒 $name · Locked"
    LockTileStatus.UNLOCKED -> "🔓 $name · Unlocked"
    LockTileStatus.LOCKING -> "🔒 $name · Locking…"
    LockTileStatus.JAMMED -> "⚠ $name · Jammed"
    LockTileStatus.UNAVAILABLE -> "$name · Unavailable"
}

/**
 * The camera row's height (dp) for a pane [paneWidth] wide: the widest real case — one 3:4 doorbell + the rest 16:9,
 * [gap] apart — fits exactly, capped at [max].
 */
fun cameraRowHeight(paneWidth: Float, count: Int, gap: Float, max: Float): Float {
    if (count <= 0) return 0f
    val aspects = 3f / 4f + (count - 1) * (16f / 9f)
    return minOf(max, (paneWidth - gap * (count - 1)) / aspects)
}

private val LastSeenClock = java.time.format.DateTimeFormatter.ofPattern("h:mm")

/** A camera's overlay after 3 failed sessions: its last frame's time, 12-hour without am/pm (like the home clock). */
fun lastSeenText(atMs: Long, zone: java.time.ZoneId): String =
    "Last seen ${LastSeenClock.format(java.time.Instant.ofEpochMilli(atMs).atZone(zone))} · Reconnecting…"

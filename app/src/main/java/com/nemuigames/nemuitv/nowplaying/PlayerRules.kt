package com.nemuigames.nemuitv.nowplaying

enum class RepeatMode { OFF, ALL, ONE }

fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}

/** Where the playhead is now, extrapolated from a session snapshot; clamped to 0..duration (no upper clamp if the length is unknown). */
fun positionAt(positionMs: Long, updatedAtMs: Long, speed: Float, playing: Boolean, nowMs: Long, durationMs: Long?): Long {
    val pos = if (playing) positionMs + ((nowMs - updatedAtMs) * speed).toLong() else positionMs
    return if (durationMs != null) pos.coerceIn(0L, maxOf(durationMs, 0L)) else maxOf(pos, 0L)
}

/** m:ss from whole seconds (floor); minutes keep counting past 59, no hours field. */
fun mmss(ms: Long): String {
    val total = maxOf(ms, 0L) / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

fun progressFraction(positionMs: Long, durationMs: Long?): Float =
    if (durationMs == null || durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** True when the media session and the API agree on the track (case/whitespace-insensitive); false if either is unknown. */
fun titleMatches(sessionTitle: String?, apiTitle: String?): Boolean =
    sessionTitle != null && apiTitle != null && sessionTitle.trim().equals(apiTitle.trim(), ignoreCase = true)

enum class PlayerGoneFocus { INPUTS, CC_BUTTON }

fun playerGoneFocus(overApp: Boolean): PlayerGoneFocus = if (overApp) PlayerGoneFocus.INPUTS else PlayerGoneFocus.CC_BUTTON

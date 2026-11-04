package com.nemuigames.nemuitv.nowplaying

/** How long a paused session keeps showing in Now Playing. */
const val PAUSED_SHOWN_MS = 10 * 60_000L

/**
 * One media session as Now Playing sees it. [lastChangeMs] is on the same clock as the `nowMs` given to
 * [chooseSession]. [stopped] = the session is NONE / STOPPED / ERROR (e.g. idle Bluetooth sessions): never shown as
 * "paused", only if it claims to be [playing].
 */
data class SessionInfo(
    val pkg: String,
    val playing: Boolean,
    val lastChangeMs: Long,
    val title: String?,
    val artist: String?,
    val stopped: Boolean = false,
)

/**
 * The session to show: the playing one (several → the most recently active); none playing → the most recent paused
 * one updated within 10 min; else none. A missing title doesn't matter here (the tile falls back to the app's name).
 */
fun chooseSession(sessions: List<SessionInfo>, nowMs: Long): SessionInfo? =
    sessions.filter { it.playing }.maxByOrNull { it.lastChangeMs }
        ?: sessions.filter { !it.stopped && nowMs - it.lastChangeMs <= PAUSED_SHOWN_MS }.maxByOrNull { it.lastChangeMs }

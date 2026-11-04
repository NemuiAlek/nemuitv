package com.nemuigames.nemuitv.nowplaying

import android.support.v4.media.session.PlaybackStateCompat

/**
 * Transport snapshot of the chosen session. [shuffle] / [repeat] are null when the session doesn't advertise the
 * action (so the UI can hide the control). [durationMs] null = unknown (missing or <= 0, e.g. a live stream).
 */
data class PlayerInfo(
    val positionMs: Long,
    val updatedAtMs: Long,
    val speed: Float,
    val playing: Boolean,
    val durationMs: Long?,
    val shuffle: Boolean?,
    val repeat: RepeatMode?,
)

/** Pure mapping from the raw session values; null [state] (no playback state) → null. */
fun playerInfoOf(
    state: Int?, position: Long, updatedAt: Long, speed: Float, actions: Long,
    durationMs: Long?, shuffleMode: Int, repeatMode: Int,
): PlayerInfo? {
    if (state == null) return null
    val canShuffle = actions and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE != 0L
    val canRepeat = actions and PlaybackStateCompat.ACTION_SET_REPEAT_MODE != 0L
    return PlayerInfo(
        positionMs = position,
        updatedAtMs = updatedAt,
        speed = speed,
        playing = state.isPlaying(),
        durationMs = durationMs?.takeIf { it > 0 },
        shuffle = if (canShuffle && shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_INVALID)
            shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE else null,
        repeat = if (canRepeat && repeatMode != PlaybackStateCompat.REPEAT_MODE_INVALID) when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
            else -> RepeatMode.OFF
        } else null,
    )
}

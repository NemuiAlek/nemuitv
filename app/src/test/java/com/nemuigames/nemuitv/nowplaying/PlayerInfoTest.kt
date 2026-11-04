package com.nemuigames.nemuitv.nowplaying

import android.media.session.PlaybackState
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInfoTest {
    private val spotifyActions = 2368383L // Streamer, 11-03: includes both bits

    @Test fun `spotifyActions advertiseShuffleAndRepeat`() {
        val p = playerInfoOf(PlaybackState.STATE_PLAYING, 30_174, 100, 1f, spotifyActions, 201_000,
            PlaybackStateCompat.SHUFFLE_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_ONE)!!
        assertEquals(true, p.shuffle); assertEquals(RepeatMode.ONE, p.repeat); assertEquals(201_000L, p.durationMs); assertTrue(p.playing)
    }

    @Test fun noActionBits_shuffleRepeatNull() {
        val p = playerInfoOf(PlaybackState.STATE_PAUSED, 0, 0, 0f, 0L, null, 0, 0)!!
        assertNull(p.shuffle); assertNull(p.repeat); assertFalse(p.playing)
    }

    @Test fun repeatModes_map() {
        assertEquals(RepeatMode.OFF, playerInfoOf(3, 0, 0, 1f, spotifyActions, null, 0, PlaybackStateCompat.REPEAT_MODE_NONE)!!.repeat)
        assertEquals(RepeatMode.ALL, playerInfoOf(3, 0, 0, 1f, spotifyActions, null, 0, PlaybackStateCompat.REPEAT_MODE_ALL)!!.repeat)
        assertEquals(RepeatMode.ALL, playerInfoOf(3, 0, 0, 1f, spotifyActions, null, 0, PlaybackStateCompat.REPEAT_MODE_GROUP)!!.repeat)
    }

    @Test fun nonPositiveDuration_isUnknown() = assertNull(playerInfoOf(3, 0, 0, 1f, 0, 0, 0, 0)!!.durationMs)

    @Test fun invalidShuffle_isUnknown() =
        assertNull(playerInfoOf(3, 0, 0, 1f, spotifyActions, null, PlaybackStateCompat.SHUFFLE_MODE_INVALID, 0)!!.shuffle)

    @Test fun invalidRepeat_isUnknown() =
        assertNull(playerInfoOf(3, 0, 0, 1f, spotifyActions, null, 0, PlaybackStateCompat.REPEAT_MODE_INVALID)!!.repeat)

    @Test fun nullState_null() = assertNull(playerInfoOf(null, 0, 0, 1f, 0, null, 0, 0))
}

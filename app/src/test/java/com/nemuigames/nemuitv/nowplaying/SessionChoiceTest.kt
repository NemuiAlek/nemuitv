package com.nemuigames.nemuitv.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChoiceTest {
    private val now = 10_000_000L
    private val min = 60_000L

    private fun s(pkg: String, playing: Boolean, ago: Long, title: String? = "T", stopped: Boolean = false) =
        SessionInfo(pkg, playing, now - ago, title, "A", stopped)

    @Test fun playing_wins() {
        val paused = s("spotify", playing = false, ago = 1_000)
        val playing = s("youtube", playing = true, ago = 5 * min)
        assertEquals(playing, chooseSession(listOf(paused, playing), now))
    }

    @Test fun twoPlaying_mostRecent() {
        val older = s("spotify", playing = true, ago = 3 * min)
        val newer = s("youtube", playing = true, ago = 1 * min)
        assertEquals(newer, chooseSession(listOf(older, newer), now))
        assertEquals(newer, chooseSession(listOf(newer, older), now))
    }

    @Test fun pausedWithin10min_shown() {
        val older = s("spotify", playing = false, ago = 9 * min)
        val newer = s("youtube", playing = false, ago = 2 * min)
        assertEquals(newer, chooseSession(listOf(older, newer), now))
        assertEquals(older, chooseSession(listOf(older), now))
    }

    @Test fun pausedOlder_none() {
        assertNull(chooseSession(listOf(s("spotify", playing = false, ago = 11 * min)), now))
        assertNull(chooseSession(emptyList(), now))
    }

    @Test fun noTitle_keepsSession() {
        val netflix = s("com.netflix.ninja", playing = true, ago = 0, title = null)
        assertEquals(netflix, chooseSession(listOf(netflix), now))
    }

    /** Bluetooth sessions sit in ERROR; NONE/STOPPED/ERROR are never shown as "paused" — only if they say playing. */
    @Test fun stoppedOrError_neverChosenUnlessPlaying() {
        val bt = s("com.google.android.bluetooth", playing = false, ago = 0, stopped = true)
        assertNull(chooseSession(listOf(bt), now))
        val paused = s("spotify", playing = false, ago = 5 * min)
        assertEquals(paused, chooseSession(listOf(bt, paused), now))
        val playingFlagged = s("x", playing = true, ago = 0, stopped = true)
        assertEquals(playingFlagged, chooseSession(listOf(bt, playingFlagged), now))
    }
}

package com.nemuigames.nemuitv.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauserTest {
    private class Fake(override val pkg: String, playing: Boolean) : MediaHandle {
        override var isPlaying = playing
        var pauses = 0
        var plays = 0
        override fun pause() { pauses++; isPlaying = false }
        override fun play() { plays++; isPlaying = true }
    }

    @Test fun pause_whenPlaying_rememberedAndPaused() {
        val h = Fake("yt", true)
        val p = MediaPauser { h.takeIf { it.isPlaying } }
        assertTrue(p.pauseIfPlaying())
        assertEquals(1, h.pauses)
        p.resumeIfWePaused()
        assertEquals(1, h.plays)
    }

    @Test fun notPlaying_noPause_noResume() {
        val h = Fake("yt", false)
        val p = MediaPauser { h.takeIf { it.isPlaying } }
        assertFalse(p.pauseIfPlaying())
        p.resumeIfWePaused()
        assertEquals(0, h.pauses)
        assertEquals(0, h.plays)
    }

    @Test fun resume_onlyWhatWePaused() {
        val a = Fake("yt", true)
        val b = Fake("music", false)
        var current: MediaHandle? = a
        val p = MediaPauser { current?.takeIf { it.isPlaying } }
        p.pauseIfPlaying()
        b.isPlaying = true
        current = b
        p.resumeIfWePaused()
        assertEquals(1, a.plays)
        assertEquals(0, b.plays)
        assertEquals(0, b.pauses)
    }

    @Test fun doublePause_pausesOnce() {
        val h = Fake("yt", true)
        val p = MediaPauser { h.takeIf { it.isPlaying } }
        assertTrue(p.pauseIfPlaying())
        assertFalse(p.pauseIfPlaying())
        assertEquals(1, h.pauses)
        p.resumeIfWePaused()
        assertEquals(1, h.plays)
    }

    @Test fun resumeTwice_playsOnce() {
        val h = Fake("yt", true)
        val p = MediaPauser { h.takeIf { it.isPlaying } }
        p.pauseIfPlaying()
        p.resumeIfWePaused()
        p.resumeIfWePaused()
        assertEquals(1, h.plays)
    }
}

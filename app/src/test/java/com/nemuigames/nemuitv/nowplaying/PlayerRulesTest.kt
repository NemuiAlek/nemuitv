package com.nemuigames.nemuitv.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRulesTest {
    @Test fun repeat_cycles() { assertEquals(RepeatMode.ALL, RepeatMode.OFF.next()); assertEquals(RepeatMode.ONE, RepeatMode.ALL.next()); assertEquals(RepeatMode.OFF, RepeatMode.ONE.next()) }
    @Test fun position_playing_advancesBySpeed() = assertEquals(12_000L, positionAt(10_000, 1_000, 1f, true, 3_000, 200_000))
    @Test fun position_paused_doesNotAdvance() = assertEquals(10_000L, positionAt(10_000, 1_000, 1f, false, 60_000, 200_000))
    @Test fun position_clampedToDuration() {
        assertEquals(200_000L, positionAt(199_000, 0, 1f, true, 5_000, 200_000))
        assertEquals(0L, positionAt(-50, 0, 1f, false, 0, 200_000))
        assertEquals(15_000L, positionAt(10_000, 0, 1f, true, 5_000, null)) // unknown length: no upper clamp
    }
    @Test fun mmss_format() { assertEquals("0:00", mmss(0)); assertEquals("0:59", mmss(59_999)); assertEquals("1:01", mmss(61_000)); assertEquals("61:00", mmss(3_660_000)) }
    @Test fun progress_fraction() { assertEquals(0.5f, progressFraction(50, 100), 0.0001f); assertEquals(0f, progressFraction(50, null), 0f); assertEquals(0f, progressFraction(50, 0), 0f); assertEquals(1f, progressFraction(150, 100), 0f) }
    @Test fun titleMatch_trimAndCase() { assertTrue(titleMatches(" Test Song ", "test song")); assertFalse(titleMatches("Test Song", "Second Song")); assertFalse(titleMatches(null, "x")); assertFalse(titleMatches("x", null)) }
    @Test fun focusAfterPlayerGone() { assertEquals(PlayerGoneFocus.INPUTS, playerGoneFocus(overApp = true)); assertEquals(PlayerGoneFocus.CC_BUTTON, playerGoneFocus(overApp = false)) }
}

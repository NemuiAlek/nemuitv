package com.nemuigames.nemuitv.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMorphTest {
    private val eps = 1e-4f

    @Test fun ends_closedIsBanner_openIsPlayer() {
        assertEquals(0f, PlayerMorph.box(0f), eps)
        assertEquals(1f, PlayerMorph.box(1f), eps)
        assertEquals(1f, PlayerMorph.bannerAlpha(0f), eps)
        assertEquals(0f, PlayerMorph.bannerAlpha(1f), eps)
        for (row in 0 until PlayerMorph.ROWS) {
            assertEquals(0f, PlayerMorph.rowAlpha(row, 0f), eps)
            assertEquals(1f, PlayerMorph.rowAlpha(row, 1f), eps)
        }
    }

    @Test fun bannerContentsLeaveBeforeRowsArrive() {
        assertEquals(0f, PlayerMorph.bannerAlpha(0.3f), eps)
        assertEquals(0f, PlayerMorph.rowAlpha(0, 0.55f), eps)
        assertTrue(PlayerMorph.rowAlpha(0, 0.6f) > 0f)
    }

    // The close flash (seen frame by frame on the emulator): the white focused banner faded in while the box
    // was still bigger than it, then the box vanished. Now the banner and the box only cross-fade at banner size.
    @Test fun bannerAndBoxCrossFadeOnlyAtBannerSize() {
        for (i in 0..100) {
            val p = i / 100f
            if (PlayerMorph.bannerAlpha(p) > 0f) assertEquals("$p", 0f, PlayerMorph.box(p), eps)
        }
        assertEquals(0f, PlayerMorph.boxAlpha(0f), eps)
        assertEquals(1f, PlayerMorph.boxAlpha(0.3f), eps)
        for (p in listOf(0f, 0.1f, 0.2f, 0.3f)) assertEquals("$p", 1f, PlayerMorph.bannerAlpha(p) + PlayerMorph.boxAlpha(p), eps)
    }

    @Test fun `rowsStaggerTopToBottom soClosingFadesBottomFirst`() {
        for (p in listOf(0.6f, 0.7f, 0.8f, 0.9f)) {
            assertTrue("$p", PlayerMorph.rowAlpha(0, p) >= PlayerMorph.rowAlpha(1, p))
            assertTrue("$p", PlayerMorph.rowAlpha(1, p) >= PlayerMorph.rowAlpha(2, p))
        }
        assertTrue(PlayerMorph.rowAlpha(0, 0.7f) > PlayerMorph.rowAlpha(2, 0.7f))
    }

    @Test fun boxFullyGrownBeforeTheLastRowFinishes() {
        assertEquals(1f, PlayerMorph.box(0.8f), eps)
        assertTrue(PlayerMorph.rowAlpha(2, 0.8f) < 1f)
    }

    @Test fun boxGrowsMonotonically() {
        var last = -1f
        for (i in 0..100) {
            val b = PlayerMorph.box(i / 100f)
            assertTrue(b >= last - eps); last = b
        }
    }

    @Test fun outOfRangeIsClamped() {
        assertEquals(0f, PlayerMorph.box(-0.5f), eps)
        assertEquals(1f, PlayerMorph.box(1.5f), eps)
        assertEquals(1f, PlayerMorph.rowAlpha(2, 2f), eps)
        assertEquals(1f, PlayerMorph.bannerAlpha(-1f), eps)
    }

    @Test fun durations() {
        assertEquals(300, PlayerMorph.OPEN_MS)
        assertEquals(260, PlayerMorph.CLOSE_MS)
    }
}

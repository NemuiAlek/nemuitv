package com.nemuigames.nemuitv.hub

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTextTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 3, h, m, 30).atZone(zone).toInstant().toEpochMilli()

    @Test fun lastSeen_format() {
        assertEquals("Last seen 7:42 · Reconnecting…", lastSeenText(at(19, 42), zone))
        assertEquals("Last seen 12:05 · Reconnecting…", lastSeenText(at(0, 5), zone))
    }

    @Test fun lastSeen_usesGivenZone() {
        // 19:42 New York = 00:42 UTC the next day.
        assertEquals("Last seen 12:42 · Reconnecting…", lastSeenText(at(19, 42), ZoneId.of("UTC")))
    }

    @Test fun cameraRowHeight_widestCaseFits_capped() {
        // 636 dp pane, 3 cameras: (636 - 16) / (0.75 + 2 × 16/9) ≈ 144 dp.
        assertEquals(144.0f, cameraRowHeight(636f, 3, 8f, 150f), 0.1f)
        assertEquals(150f, cameraRowHeight(636f, 1, 8f, 150f), 0f)
        assertEquals(0f, cameraRowHeight(636f, 0, 8f, 150f), 0f)
    }
}

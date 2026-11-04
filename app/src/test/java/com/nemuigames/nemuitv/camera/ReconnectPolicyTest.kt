package com.nemuigames.nemuitv.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun firstDelay_zeroBeforeFailures() {
        assertEquals(0L, ReconnectPolicy { 0.5 }.nextDelayMs())
    }

    @Test fun doublingCapped() {
        val p = ReconnectPolicy { 0.5 }
        val delays = (1..6).map { p.onSessionFailed(false); p.nextDelayMs() }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), delays)
    }

    @Test fun jitterBounds() {
        val low = ReconnectPolicy { 0.0 }.apply { onSessionFailed(false) }
        assertEquals(8_000L, low.nextDelayMs())
        val high = ReconnectPolicy { 0.9999999 }.apply { onSessionFailed(false) }
        assertTrue(high.nextDelayMs() < 12_000L)
    }

    @Test fun rateLimited_atLeast60s() {
        val p = ReconnectPolicy { 0.0 }
        p.onSessionFailed(true)
        assertEquals(60_000L, p.nextDelayMs())
        p.onSessionFailed(false)
        assertEquals(16_000L, p.nextDelayMs())
    }

    @Test fun shortSession_doesNotReset() {
        val p = ReconnectPolicy { 0.5 }
        p.onSessionFailed(false); p.onSessionFailed(false)
        p.onSessionEnded(59_000)
        assertEquals(2, p.failuresInRow)
    }

    @Test fun healthySession_resets() {
        val p = ReconnectPolicy { 0.5 }
        p.onSessionFailed(false); p.onSessionFailed(false)
        p.onSessionEnded(60_000)
        assertEquals(0, p.failuresInRow)
    }

    @Test fun reset_clearsFailures() {
        val p = ReconnectPolicy { 0.5 }
        repeat(3) { p.onSessionFailed(true) }
        p.reset()
        assertEquals(0, p.failuresInRow)
        assertEquals(0L, p.nextDelayMs())
    }
}

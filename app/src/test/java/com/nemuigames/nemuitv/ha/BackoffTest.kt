package com.nemuigames.nemuitv.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun sequence_capsAt30s_andResets() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), List(7) { b.nextDelayMs() })
        b.reset()
        assertEquals(1000L, b.nextDelayMs())
    }
}

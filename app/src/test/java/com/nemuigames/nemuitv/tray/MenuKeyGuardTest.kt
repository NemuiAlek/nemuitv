package com.nemuigames.nemuitv.tray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuKeyGuardTest {
    /** Feeds events in order; returns which ones the guard swallowed. */
    private fun run(vararg events: Triple<Boolean, Boolean, Int>): List<Boolean> {
        val g = MenuKeyGuard()
        return events.map { (down, ok, repeat) -> g.swallow(isDown = down, isOk = ok, repeatCount = repeat) }
    }
    private fun down(ok: Boolean = true, repeat: Int = 0) = Triple(true, ok, repeat)
    private fun up(ok: Boolean = true) = Triple(false, ok, 0)

    @Test fun `realRemoteHold repeatsThenRelease releaseIsSwallowed`() {
        // Menu opened while OK still held: the remote keeps sending repeated downs, then the release.
        val swallowed = run(down(repeat = 5), down(repeat = 6), down(repeat = 7), up())
        assertTrue("the release of the long-press must not click an option", swallowed.last())
    }

    @Test fun freshPressAfterwards_clicksNormally() {
        val swallowed = run(up(), down(), up())   // opening release, then a real press + release
        assertTrue(swallowed[0]); assertFalse(swallowed[2])
    }

    @Test fun navigationKeysAlwaysPass() {
        assertFalse(run(down(ok = false), up(ok = false)).any { it })
    }

    @Test fun `repeatedDownsOfOk areSwallowedWhileUnarmed`() {
        assertTrue(run(down(repeat = 3)).single())
    }
}

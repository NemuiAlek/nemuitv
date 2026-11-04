package com.nemuigames.nemuitv.ha

import com.nemuigames.nemuitv.config.TvConfig
import com.nemuigames.nemuitv.config.TvInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaPresenceTest {
    @Test fun haUiHiddenOnlyWhenNotConfigured() {
        assertFalse(HaPresence.showsHaUi(HaStatus.NOT_CONFIGURED))
        listOf(HaStatus.CONNECTING, HaStatus.CONNECTED, HaStatus.AUTH_FAILED, HaStatus.DISCONNECTED)
            .forEach { assertTrue(it.name, HaPresence.showsHaUi(it)) }
    }

    @Test fun notConfiguredNeverShowsProblem() {
        assertFalse(HaPresence.showsProblem(HaStatus.NOT_CONFIGURED, 0L, 10_000_000L))
    }

    @Test fun authFailedAlwaysShowsProblem() {
        assertTrue(HaPresence.showsProblem(HaStatus.AUTH_FAILED, null, 0L))
    }

    @Test fun disconnectedShowsProblemOnlyWhenStale() {
        val since = 1_000L
        assertTrue(HaPresence.showsProblem(HaStatus.DISCONNECTED, since, since + STALE_AFTER_MS + 1))
        assertFalse(HaPresence.showsProblem(HaStatus.DISCONNECTED, since, since + 1))
        assertFalse(HaPresence.showsProblem(HaStatus.CONNECTED, null, 5L))
    }

    @Test fun inputsNeedTvInputsAndHa() {
        val tv = TvConfig("media_player.tv", listOf(TvInput("A", "a")))
        assertFalse(inputsAvailable(null, HaStatus.CONNECTED))
        assertFalse(inputsAvailable(tv, HaStatus.NOT_CONFIGURED))
        assertFalse(inputsAvailable(TvConfig("media_player.tv", emptyList()), HaStatus.CONNECTED))
        assertTrue(inputsAvailable(tv, HaStatus.CONNECTED))
    }
}

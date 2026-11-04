package com.nemuigames.nemuitv.ambient

import com.nemuigames.nemuitv.config.LofiConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LofiTest {
    private data class Call(val service: String, val data: JsonObject, val atMs: Long)

    private val cfg = LofiConfig("media_player.spotify", "Living Room TV", "spotify:playlist:abc", 0.15)

    private class Rig(scope: TestScope, cfg: LofiConfig?, var haUp: Boolean = true, var playing: () -> Boolean = { false }) {
        val calls = mutableListOf<Call>()
        val dreaming = MutableStateFlow(true)
        var polls = 0
        val lofi = Lofi(
            callService = { domain, service, data ->
                assertEquals("media_player", domain)
                if (haUp) calls += Call(service, data, scope.currentTime)
                haUp
            },
            scope = scope,
            config = { cfg },
            isSpotifyPlaying = { polls++; playing() },
            dreaming = dreaming,
        )
    }

    @Test fun start_sendsSequenceInOrder() = runTest {
        val r = Rig(this, cfg)
        r.lofi.start()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("select_source", "volume_set", "play_media"), r.calls.map { it.service })
        val (a, b, c) = r.calls
        assertEquals(3_000, b.atMs - a.atMs)
        assertEquals("media_player.spotify", a.data["entity_id"]!!.jsonPrimitive.content)
        assertEquals("Living Room TV", a.data["source"]!!.jsonPrimitive.content)
        assertEquals(0.15, b.data["volume_level"]!!.jsonPrimitive.double, 0.0)
        assertTrue(b.data["volume_level"] is JsonPrimitive && !b.data["volume_level"]!!.jsonPrimitive.isString)
        assertEquals("media_player.spotify", b.data["entity_id"]!!.jsonPrimitive.content)
        assertEquals("playlist", c.data["media_content_type"]!!.jsonPrimitive.content)
        assertEquals("spotify:playlist:abc", c.data["media_content_id"]!!.jsonPrimitive.content)
        assertEquals("media_player.spotify", c.data["entity_id"]!!.jsonPrimitive.content)
    }

    @Test fun `start haOffline sendsNothingAndDoesNotRetry`() = runTest {
        val r = Rig(this, cfg, haUp = false)
        r.lofi.start()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(r.calls.isEmpty())
        assertEquals(0, r.polls)
    }

    @Test fun `start spotifyPlayingWithin10s stopsPolling`() = runTest {
        val r = Rig(this, cfg, playing = { currentTime >= 3_000 + 4_000 })
        r.lofi.start()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, r.calls.size)
        assertTrue("polled ${r.polls}", r.polls in 1..6)
    }

    @Test fun `start notPlayingAfter10s stopsWithNoFurtherCalls`() = runTest {
        val r = Rig(this, cfg)
        r.lofi.start()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, r.calls.size)
        // Polled over about 10 s (3 s wait + 10 s), not forever.
        assertTrue("polled ${r.polls}", r.polls in 10..12)
        assertTrue(currentTime <= 60_000)
    }

    @Test fun remainingCalls_dropped_whenDreamEnded() = runTest {
        val r = Rig(this, cfg)
        r.lofi.start()
        launch { delay(1_000); r.dreaming.value = false }
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("select_source"), r.calls.map { it.service })
    }

    @Test fun start_whileRunning_ignored() = runTest {
        val r = Rig(this, cfg)
        r.lofi.start()
        advanceTimeBy(1_000)
        r.lofi.start()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, r.calls.size)
    }

    @Test fun start_noConfig_sendsNothing() = runTest {
        val r = Rig(this, null)
        r.lofi.start()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(r.calls.isEmpty())
    }
}

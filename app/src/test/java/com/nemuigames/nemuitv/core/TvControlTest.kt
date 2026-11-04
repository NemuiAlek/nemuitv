package com.nemuigames.nemuitv.core

import com.nemuigames.nemuitv.config.HaConfig
import com.nemuigames.nemuitv.config.TvConfig
import com.nemuigames.nemuitv.config.TvInput
import com.nemuigames.nemuitv.ha.FakeHa
import com.nemuigames.nemuitv.ha.HaClient
import com.nemuigames.nemuitv.ha.HaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TvControlTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<MockWebServer>()
    private val tvCfg = TvConfig("media_player.tv", listOf(TvInput("Switch", "HDMI 2")))

    @After fun tearDown() { scope.cancel(); servers.forEach { runCatching { it.shutdown() } } }

    private fun <T> waitFor(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun connected(fake: FakeHa, tv: String? = "media_player.tv"): HaClient {
        val s = MockWebServer().also { it.enqueue(MockResponse().withWebSocketUpgrade(fake)); it.start(); servers += it }
        val c = HaClient(scope, OkHttpClient(), sleep = { kotlinx.coroutines.awaitCancellation() })
        c.start(HaConfig(s.url("/").toString(), "T"), tv)
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
        return c
    }

    /** Next frame that is a call_service. */
    private fun FakeHa.nextCall(): String {
        while (true) { val f = received.poll(2, TimeUnit.SECONDS) ?: error("no call_service frame"); if ("call_service" in f) return f }
    }

    @Test fun `callService whenConnected sendsExactJson`() {
        val fake = FakeHa()
        val c = connected(fake)
        assertTrue(c.callService("media_player", "volume_up", buildJsonObject { put("entity_id", "media_player.tv") }))
        assertEquals(
            """{"id":2,"type":"call_service","domain":"media_player","service":"volume_up","service_data":{"entity_id":"media_player.tv"}}""",
            fake.nextCall(),
        )
    }

    @Test fun `callService whenOffline returnsFalse sendsNothingLater`() {
        val fake = FakeHa()
        val c = HaClient(scope, OkHttpClient())
        assertFalse(c.callService("media_player", "volume_up", buildJsonObject {}))
        val s = MockWebServer().also { it.enqueue(MockResponse().withWebSocketUpgrade(fake)); it.start(); servers += it }
        c.start(HaConfig(s.url("/").toString(), "T"), null)
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
        Thread.sleep(300)
        assertTrue(fake.received.none { "call_service" in it })
    }

    @Test fun selectInput_sendsSource() {
        val fake = FakeHa()
        val c = connected(fake)
        val tv = TvControl(c, { tvCfg }, scope)
        assertTrue(tv.selectInput(tvCfg.inputs[0]))
        val frame = fake.nextCall()
        assertTrue(frame, "\"service\":\"select_source\"" in frame && "\"source\":\"HDMI 2\"" in frame)
    }

    @Test fun `switchToThisDevice onlyWhenOnAnotherKnownInput`() {
        assertTrue(shouldSwitchToThisDevice(source = "PS5 Game Console", thisDevice = "Google OTT"))
        assertFalse(shouldSwitchToThisDevice(source = "Google OTT", thisDevice = "Google OTT")) // already here
        assertFalse(shouldSwitchToThisDevice(source = null, thisDevice = "Google OTT")) // HA offline / unknown
        assertFalse(shouldSwitchToThisDevice(source = "PS5 Game Console", thisDevice = null)) // not configured
    }

    @Test fun status_notSetUp_whenNoTvConfig() {
        val tv = TvControl(HaClient(scope, OkHttpClient()), { null }, scope)
        assertEquals(TvStatus.NOT_SET_UP, tv.status.value)
        assertFalse(tv.selectInput(tvCfg.inputs[0]))
        val tv2 = TvControl(HaClient(scope, OkHttpClient()), { tvCfg }, scope)
        assertEquals(TvStatus.HA_OFFLINE, tv2.status.value)
    }
}

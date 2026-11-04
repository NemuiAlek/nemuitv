package com.nemuigames.nemuitv.ha

import com.nemuigames.nemuitv.config.HaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class HaClientTest {
    private val servers = mutableListOf<MockWebServer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val delays: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val gate = Channel<Unit>(Channel.UNLIMITED)

    /** Sleep function for the client: records the requested delay, then waits for the test to open the gate. */
    private val gatedSleep: suspend (Long) -> Unit = { ms -> delays += ms; gate.receive() }

    private fun server(vararg responses: MockResponse) = MockWebServer().also { s ->
        responses.forEach(s::enqueue); s.start(); servers += s
    }
    private fun upgrade(fake: FakeHa) = MockResponse().withWebSocketUpgrade(fake)
    private fun cfg(s: MockWebServer, token: String = "T") = HaConfig(s.url("/").toString(), token)
    private fun client(sleep: suspend (Long) -> Unit = gatedSleep) = HaClient(scope, OkHttpClient(), sleep = sleep)

    private fun <T> waitFor(timeoutMs: Long = 5_000, block: suspend () -> T): T = runBlocking { withTimeout(timeoutMs) { block() } }
    private fun eventually(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "condition not met in time" }; Thread.sleep(20) }
    }

    @After fun tearDown() { scope.cancel(); servers.forEach { runCatching { it.shutdown() } } }

    @Test fun connect_authsThenSubscribes() {
        val fake = FakeHa()
        val s = server(upgrade(fake))
        val c = client()
        c.start(cfg(s))

        assertEquals(WeatherSnapshot("sunny", 78.0, "°F"), waitFor { c.weather.first { it != null } })
        assertEquals(HaStatus.CONNECTED, c.status.value)
        assertTrue(c.weatherUpdatedAt.value != null)
        assertEquals("""{"type":"auth","access_token":"T"}""", fake.received.poll(1, TimeUnit.SECONDS))
        assertEquals("""{"id":1,"type":"subscribe_entities","entity_ids":["weather.forecast_home"]}""", fake.received.poll(1, TimeUnit.SECONDS))
        assertEquals("/api/websocket", s.takeRequest().path)
    }

    @Test fun `authInvalid setsAuthFailed retriesAfter5Min`() {
        val s = server(upgrade(FakeHa(acceptToken = "other")), upgrade(FakeHa(acceptToken = "other")))
        val c = client()
        c.start(cfg(s))

        waitFor { c.status.first { it == HaStatus.AUTH_FAILED } }
        eventually { delays.isNotEmpty() }
        assertEquals(listOf(300_000L), delays.toList())
        Thread.sleep(200)
        assertEquals("no reconnect before the 5-minute wait ends", 1, s.requestCount)

        gate.trySend(Unit)
        eventually { s.requestCount == 2 }
    }

    @Test fun `serverDrop reconnectsWithBackoff resetAfterAuth`() {
        val s = server(upgrade(FakeHa(dropAfterEvent = true)), upgrade(FakeHa(dropAfterEvent = true)), upgrade(FakeHa()))
        val c = client()
        c.start(cfg(s))

        eventually { delays.size == 1 }
        assertEquals(1000L, delays[0])
        gate.trySend(Unit)
        eventually { delays.size == 2 }
        assertEquals("backoff resets after a successful auth", 1000L, delays[1])
        gate.trySend(Unit)
        eventually { s.requestCount == 3 }
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
    }

    @Test fun serverDown_backoffGrows() {
        val s = server(MockResponse().setResponseCode(502), MockResponse().setResponseCode(502), upgrade(FakeHa()))
        val c = client()
        c.start(cfg(s))

        eventually { delays.size == 1 }; gate.trySend(Unit)
        eventually { delays.size == 2 }; gate.trySend(Unit)
        assertEquals(listOf(1000L, 2000L), delays.toList())
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
    }

    @Test fun `start withNewConfig reconnectsToNewServer`() {
        val fakeA = FakeHa(); val fakeB = FakeHa(acceptToken = "B")
        val a = server(upgrade(fakeA)); val b = server(upgrade(fakeB))
        val c = client()
        c.start(cfg(a))
        waitFor { c.status.first { it == HaStatus.CONNECTED } }

        c.start(cfg(b, token = "B"))
        eventually { fakeB.received.any { it.contains("\"access_token\":\"B\"") } }
        eventually { fakeA.closed }
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
        assertEquals(1, b.requestCount)
    }

    @Test fun start_sameConfig_isNoOp() {
        val s = server(upgrade(FakeHa()))
        val c = client()
        c.start(cfg(s))
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
        c.start(cfg(s))
        Thread.sleep(200)
        assertEquals(1, s.requestCount)
        assertEquals(HaStatus.CONNECTED, c.status.value)
    }

    @Test fun start_null_isNotConfigured() {
        val c = client()
        c.start(null)
        assertEquals(HaStatus.NOT_CONFIGURED, c.status.value)
    }

    @Test fun `start withUnusableUrl doesNotCrash staysDisconnected`() {
        val c = client()
        c.start(HaConfig("not a url at all", "T"))   // OkHttp would throw on this
        eventually { delays.isNotEmpty() }
        assertEquals(HaStatus.DISCONNECTED, c.status.value)
    }

    @Test fun defaultHttpClient_pingsEvery30s() {
        assertEquals(30_000, HaClient.defaultHttpClient().pingIntervalMillis)
    }

    @Test fun `disconnectedSince setOnDrop clearedOnReconnect`() {
        var clock = 1_000L
        val s = server(upgrade(FakeHa(dropAfterEvent = true)), upgrade(FakeHa()))
        val c = HaClient(scope, OkHttpClient(), now = { clock }, sleep = gatedSleep)
        c.start(cfg(s))
        eventually { delays.size == 1 }
        assertEquals(1_000L, c.disconnectedSince.value)
        clock = 5_000L
        gate.trySend(Unit)
        waitFor { c.status.first { it == HaStatus.CONNECTED } }
        assertEquals(null, c.disconnectedSince.value)
    }

    @Test fun weatherEntityMissing_reportsProblem() {
        val s = server(upgrade(FakeHa(entityExists = false)))
        val c = client()
        c.start(cfg(s))
        assertEquals("Weather entity not found: weather.forecast_home", waitFor { c.weatherProblem.first { it != null } })
    }

    @Test fun subscribeRejected_reportsProblem() {
        val s = server(upgrade(FakeHa(subscribeSucceeds = false)))
        val c = client()
        c.start(cfg(s))
        assertEquals("Weather subscription failed", waitFor { c.weatherProblem.first { it != null } })
    }

    @Test fun weatherOk_noProblem() {
        val s = server(upgrade(FakeHa()))
        val c = client()
        c.start(cfg(s))
        waitFor { c.weather.first { it != null } }
        assertEquals(null, c.weatherProblem.value)
    }

    @Test fun `spotifyEntity subscribedWithTv andStateExposed`() {
        val fake = FakeHa()
        val s = server(upgrade(fake))
        val c = client()
        c.start(cfg(s), "media_player.tv", "media_player.spotify")

        assertEquals("playing", waitFor { c.spotifyState.first { it != null } })
        fake.received.poll(1, TimeUnit.SECONDS) // auth
        assertEquals(
            """{"id":1,"type":"subscribe_entities","entity_ids":["weather.forecast_home","media_player.tv","media_player.spotify"]}""",
            fake.received.poll(1, TimeUnit.SECONDS),
        )
        assertEquals(TvState(true, "HDMI 1"), waitFor { c.tvState.first { it != null } })
    }

    @Test fun `spotifyEntity changed restartsAndClearsState`() {
        val s = server(upgrade(FakeHa()), upgrade(FakeHa()))
        val c = client()
        c.start(cfg(s), null, "media_player.spotify")
        waitFor { c.spotifyState.first { it != null } }
        c.start(cfg(s), null, null)
        assertEquals(null, c.spotifyState.value)
        eventually { s.requestCount == 2 }
    }
}

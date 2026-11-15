package com.nemuigames.nemuitv.ha

import com.nemuigames.nemuitv.config.HaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HaClientRequestTest {
    private val servers = mutableListOf<MockWebServer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Channel<Unit>(Channel.UNLIMITED)
    private val gatedSleep: suspend (Long) -> Unit = { gate.receive() }

    private fun server(vararg fakes: FakeHa) = MockWebServer().also { s ->
        fakes.forEach { s.enqueue(MockResponse().withWebSocketUpgrade(it)) }; s.start(); servers += s
    }
    private fun cfg(s: MockWebServer) = HaConfig(s.url("/").toString(), "T")
    private fun client() = HaClient(scope, OkHttpClient(), sleep = gatedSleep)
    private fun <T> waitFor(timeoutMs: Long = 5_000, block: suspend () -> T): T = runBlocking { withTimeout(timeoutMs) { block() } }
    private fun connected(c: HaClient, s: MockWebServer) { c.start(cfg(s)); waitFor { c.status.first { it == HaStatus.CONNECTED } } }
    private fun id(msg: JsonObject) = msg["id"]!!.jsonPrimitive.int

    @After fun tearDown() { scope.cancel(); servers.forEach { runCatching { it.shutdown() } } }

    @Test fun request_ok_returnsResult() {
        val fake = FakeHa(respond = { type, msg, ws ->
            (type == "config/area_registry/list").also {
                if (it) ws.send("""{"id":${id(msg)},"type":"result","success":true,"result":[{"area_id":"kitchen","name":"Kitchen"}]}""")
            }
        })
        val c = client(); connected(c, server(fake))
        // A payload can't overwrite the frame's own id / type.
        val r = waitFor { c.request("config/area_registry/list", buildJsonObject { put("id", 999); put("type", "bogus") }) }
        assertEquals(1, ((r as HaResult.Ok).result as JsonArray).size)
        val sent = fake.received.toList().map { Json.parseToJsonElement(it).jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "config/area_registry/list" }
        assertTrue(id(sent) != 999)
    }

    @Test fun request_error_returnsError() {
        val fake = FakeHa(respond = { type, msg, ws ->
            (type == "x/y").also {
                if (it) ws.send("""{"id":${id(msg)},"type":"result","success":false,"error":{"code":"unauthorized","message":"x"}}""")
            }
        })
        val c = client(); connected(c, server(fake))
        assertEquals(HaResult.Error(HaError("unauthorized", "x")), waitFor { c.request("x/y") })
    }

    @Test fun request_notConnected() {
        val fake = FakeHa()
        val s = server(fake)
        val c = client()
        assertEquals(HaResult.NotConnected, waitFor { c.request("x/y") })
        assertEquals(0, s.requestCount)
        assertTrue(fake.received.isEmpty())
    }

    @Test fun request_timeout() {
        val c = client(); connected(c, server(FakeHa(respond = { type, _, _ -> type == "x/y" })))
        assertEquals(HaResult.Timeout, waitFor { c.request("x/y", timeoutMs = 300) })
    }

    @Test fun `callServiceForResult honorsItsTimeout`() {
        val c = client(); connected(c, server(FakeHa(respond = { type, _, _ -> type == "call_service" })))
        assertEquals(HaResult.Timeout, waitFor { c.callServiceForResult("climate", "turn_on", buildJsonObject { }, timeoutMs = 300) })
    }

    @Test fun request_dropMidway_returnsNotConnected() {
        val c = client(); connected(c, server(FakeHa(respond = { type, _, ws -> (type == "x/y").also { if (it) ws.close(1001, "bye") } })))
        assertEquals(HaResult.NotConnected, waitFor { c.request("x/y") })
    }

    @Test fun `subscribe receivesEvents closeSendsUnsubscribe`() {
        val fake = FakeHa(respond = { type, msg, ws ->
            (type == "subscribe_events").also {
                if (it) {
                    val i = id(msg)
                    ws.send("""{"id":$i,"type":"result","success":true,"result":null}""")
                    ws.send("""{"id":$i,"type":"event","event":{"n":1}}""")
                    ws.send("""{"id":$i,"type":"event","event":{"n":2}}""")
                }
            }
        })
        val c = client(); connected(c, server(fake))
        val sub = waitFor { c.subscribe("subscribe_events") }
        assertNotNull(sub)
        val got = waitFor { sub!!.events.take(2).toList().map { it["n"]!!.jsonPrimitive.int } }
        assertEquals(listOf(1, 2), got)
        val subId = fake.received.toList().map { Json.parseToJsonElement(it).jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "subscribe_events" }.let(::id)
        sub!!.close()
        val unsub = generateSequence { fake.received.poll(2, TimeUnit.SECONDS) }.first { "unsubscribe_events" in it }
        assertEquals(subId, Json.parseToJsonElement(unsub).jsonObject["subscription"]!!.jsonPrimitive.int)
    }

    @Test fun connectionCount_incrementsPerAuth() {
        val s = server(FakeHa(dropAfterEvent = true), FakeHa())
        val c = client()
        c.start(cfg(s))
        waitFor { c.connectionCount.first { it == 1 } }
        gate.trySend(Unit)
        waitFor { c.connectionCount.first { it == 2 } }
        assertEquals(2, c.connectionCount.value)
    }

    @Test fun `connectionCount change requestFromCollectorSucceeds`() {
        val fake = FakeHa(respond = { type, msg, ws ->
            (type == "config/area_registry/list").also {
                if (it) ws.send("""{"id":${id(msg)},"type":"result","success":true,"result":[]}""")
            }
        })
        val s = server(fake)
        val c = client()
        val outcome = kotlinx.coroutines.CompletableDeferred<HaResult>()
        scope.launch(Dispatchers.Default) {
            c.connectionCount.first { it > 0 }
            outcome.complete(c.request("config/area_registry/list"))
        }
        c.start(cfg(s))
        assertTrue(waitFor { outcome.await() } is HaResult.Ok)
    }

    @Test fun callServiceForResult_passesTarget() {
        val fake = FakeHa()
        val c = client(); connected(c, server(fake))
        val r = waitFor { c.callServiceForResult("light", "turn_on", buildJsonObject { put("brightness", 10) }, buildJsonObject { put("entity_id", "light.x") }) }
        assertTrue(r is HaResult.Ok)
        val frames = generateSequence { fake.received.poll(1, TimeUnit.SECONDS) }.toList()
        assertTrue(frames.any { """"target":{"entity_id":"light.x"}""" in it })
    }
    private fun receivedSubscribeId(fake: FakeHa, type: String): Int =
        generateSequence { fake.received.poll(2, TimeUnit.SECONDS) }
            .map { Json.parseToJsonElement(it).jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == type }.let(::id)

    private fun unsubscribedId(fake: FakeHa): Int {
        val unsub = generateSequence { fake.received.poll(2, TimeUnit.SECONDS) }.first { "unsubscribe_events" in it }
        return Json.parseToJsonElement(unsub).jsonObject["subscription"]!!.jsonPrimitive.int
    }

    @Test fun `subscribe cancelledDuringAwait removesEntryAndUnsubscribes`() {
        // HA never answers the subscribe; the caller gives up (e.g. the hub closes) while it waits.
        val fake = FakeHa(respond = { type, _, _ -> type == "subscribe_events" })
        val c = client(); connected(c, server(fake))
        val job = scope.launch { c.subscribe("subscribe_events") }
        val subId = receivedSubscribeId(fake, "subscribe_events")
        assertEquals(1, c.openSubscriptionCount())
        runBlocking { job.cancel(); job.join() }
        assertEquals(0, c.openSubscriptionCount())
        assertEquals(subId, unsubscribedId(fake))
    }

    @Test fun subscribe_timeout_unsubscribes() {
        val fake = FakeHa(respond = { type, _, _ -> type == "subscribe_events" })
        val c = HaClient(scope, OkHttpClient(), sleep = gatedSleep, subscribeTimeoutMs = 300)
        connected(c, server(fake))
        assertEquals(null, waitFor { c.subscribe("subscribe_events") })
        assertEquals(0, c.openSubscriptionCount())
        val subId = receivedSubscribeId(fake, "subscribe_events")
        assertEquals(subId, unsubscribedId(fake))
    }

    @Test fun request_cancelled_removesPending() {
        val answerNow = java.util.concurrent.CompletableFuture<Unit>()
        val fake = FakeHa(respond = { type, msg, ws ->
            (type == "x/slow").also {
                if (it) Thread { answerNow.get(); ws.send("""{"id":${id(msg)},"type":"result","success":true,"result":null}""") }.start()
            }
        })
        val c = client(); connected(c, server(fake))
        val job = scope.launch { c.request("x/slow", timeoutMs = 60_000) }
        waitFor { while (c.pendingCount == 0) kotlinx.coroutines.delay(10) }
        assertEquals(1, c.pendingCount)
        job.cancel()
        waitFor { job.join() }
        assertEquals(0, c.pendingCount)
        answerNow.complete(Unit) // a late answer must not crash anything
        waitFor { while (fake.received.none { it.contains("x/slow") }) kotlinx.coroutines.delay(10) }
        Thread.sleep(200)
        assertEquals(0, c.pendingCount)
        assertEquals(HaStatus.CONNECTED, c.status.value)
    }
}

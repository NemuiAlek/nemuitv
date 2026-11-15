package com.nemuigames.nemuitv.alerts

import com.nemuigames.nemuitv.config.AlertsConfig
import com.nemuigames.nemuitv.ha.HaError
import com.nemuigames.nemuitv.ha.HaPort
import com.nemuigames.nemuitv.ha.HaResult
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.ha.HaSubscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun j(s: String) = Json.parseToJsonElement(s)

private class AlertSub : HaSubscription {
    val channel = Channel<JsonObject>(Channel.UNLIMITED)
    var closed = false
    override val events: Flow<JsonObject> = channel.receiveAsFlow()
    override fun close() { closed = true; channel.close() }
}

private const val BELL = "event.front_yard_doorbell_chime"
private const val CAM = "camera.front_yard_doorbell"
private const val WASHER = "event.washer_notification"
private const val DRYER = "event.dryer_notification"
private const val OTHER = "event.other_notification"
private const val BARE_BELL = "event.side_bell_chime"

private class AlertFakePort : HaPort {
    override val status = MutableStateFlow(HaStatus.CONNECTED)
    override val connectionCount = MutableStateFlow(1)
    val subs = mutableListOf<AlertSub>()
    val subPayloads = mutableListOf<JsonObject>()

    var throwOnRegistry = false
    val requests = mutableListOf<String>()

    var entities: JsonElement = j("""[
        {"entity_id":"$BELL","device_id":"d_bell","labels":["lbl_alert"]},
        {"entity_id":"$CAM","device_id":"d_bell","labels":[]},
        {"entity_id":"$WASHER","device_id":"d_washer","labels":["lbl_alert"]},
        {"entity_id":"$DRYER","device_id":"d_dryer","labels":["lbl_alert"]},
        {"entity_id":"$OTHER","device_id":"d_other","labels":["lbl_alert"]},
        {"entity_id":"$BARE_BELL","device_id":"d_side","labels":["lbl_alert"]},
        {"entity_id":"event.unlabelled","device_id":"d_washer","labels":[]}
    ]""")

    private fun registry(type: String): JsonElement? = when (type) {
        "config/area_registry/list" -> j("""[{"area_id":"front_yard","name":"Front Yard"},{"area_id":"laundry","name":"Laundry Room"}]""")
        "config/device_registry/list" -> j("""[
            {"id":"d_bell","area_id":"front_yard","name":"Front Yard Doorbell","manufacturer":"Ring"},
            {"id":"d_side","area_id":"front_yard","name":"Side Bell","manufacturer":"Ring"},
            {"id":"d_washer","area_id":"laundry","name":"Washer","manufacturer":"LG"},
            {"id":"d_dryer","area_id":"laundry","name":"Dryer","manufacturer":"LG"},
            {"id":"d_other","area_id":"laundry","name":"Fridge","manufacturer":"LG"}
        ]""")
        "config/entity_registry/list" -> entities
        "config/label_registry/list" -> j("""[{"label_id":"lbl_alert","name":"nemuitv_alert"}]""")
        else -> null
    }

    override suspend fun request(type: String, payload: JsonObject, timeoutMs: Long): HaResult {
        requests += type
        if (status.value != HaStatus.CONNECTED) return HaResult.NotConnected
        if (throwOnRegistry) throw IllegalStateException("odd HA json")
        return registry(type)?.let { HaResult.Ok(it) } ?: HaResult.Error(HaError("unknown", type))
    }

    override suspend fun subscribe(type: String, payload: JsonObject): HaSubscription? {
        if (status.value != HaStatus.CONNECTED || type != "subscribe_entities") return null
        subPayloads += payload
        return AlertSub().also { subs += it }
    }

    override suspend fun callServiceForResult(
        domain: String, service: String, data: JsonObject, target: JsonObject?, timeoutMs: Long,
    ): HaResult =
        HaResult.Ok(null)

    fun emit(event: String) { subs.last().channel.trySend(j(event).jsonObject) }
}

private class FakeMedia(override var isPlaying: Boolean = true) : MediaHandle {
    override val pkg = "com.netflix.ninja"
    var pauses = 0
    var plays = 0
    override fun pause() { pauses++; isPlaying = false }
    override fun play() { plays++; isPlaying = true }
}

private val BASE_MS = Instant.parse("2026-11-03T14:00:00Z").toEpochMilli()

@OptIn(ExperimentalCoroutinesApi::class)
class AlertCenterTest {
    private val port = AlertFakePort()
    private var tvOn = true
    private val media = FakeMedia()
    private var mediaPresent = true
    private val pauser = MediaPauser { media.takeIf { mediaPresent } }
    private val acquired = mutableListOf<String>()
    private val released = mutableListOf<String>()

    private fun TestScope.now(): Long = BASE_MS + testScheduler.currentTime

    /** The ISO timestamp of "now" plus [offsetMs] (event entities' state). */
    private fun TestScope.ts(offsetMs: Long = 0): String = Instant.ofEpochMilli(now() + offsetMs).toString()

    private fun TestScope.started(): AlertCenter {
        val c = AlertCenter(
            port, { AlertsConfig() }, backgroundScope, { now() }, { tvOn }, pauser,
            { acquired += it }, { released += it },
        )
        c.start()
        runCurrent()
        // HA's first event: the remembered states (never fired).
        initial()
        return c
    }

    private fun TestScope.initial() {
        val old = ts(-3_600_000)
        port.emit("""{"a":{
            "$BELL":{"s":"$old","a":{"event_type":"ring","event_types":["ring"],"friendly_name":"Front Yard Doorbell Chime"}},
            "$CAM":{"s":"idle","a":{"friendly_name":"Front door"}},
            "$WASHER":{"s":"$old","a":{"event_type":"washing_is_complete","event_types":["washing_is_complete","error_during_washing"]}},
            "$DRYER":{"s":"$old","a":{"event_type":"drying_is_complete","event_types":["drying_is_complete"]}},
            "$OTHER":{"s":"$old","a":{"event_type":"door_open","event_types":["door_open"]}},
            "$BARE_BELL":{"s":"$old","a":{"event_type":"ring","event_types":["ring"]}}
        }}""")
        runCurrent()
    }

    private fun TestScope.fire(id: String, type: String) {
        port.emit("""{"c":{"$id":{"+":{"s":"${ts()}","a":{"event_type":"$type"}}}}}""")
        runCurrent()
    }

    private fun TestScope.ring() = fire(BELL, "ring")

    private fun assertBalanced() {
        assertEquals(acquired.size, released.size)
        assertEquals(acquired.sorted(), released.sorted())
    }

    @Test fun subscribes_alertEntitiesAndTheirCamera() = runTest {
        started()
        val ids = port.subPayloads.single()["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf(BELL, CAM, WASHER, DRYER, OTHER, BARE_BELL), ids)
    }

    @Test fun firstEvent_neverFires() = runTest {
        val c = started()
        assertNull(c.ui.value.doorbell)
        assertTrue(c.ui.value.banners.isEmpty())
        assertTrue(acquired.isEmpty())
    }

    @Test fun ring_showsDoorbell_acquiresFeed_once() = runTest {
        val c = started()
        ring()
        val d = c.ui.value.doorbell
        assertNotNull(d)
        assertEquals(CAM, d!!.cameraId)
        assertEquals("Front door", d.name)
        assertFalse(d.expanded)
        assertEquals(now(), d.ringAtMs)
        assertEquals(listOf(CAM), acquired)
        assertTrue(c.capturesKeys.value)
    }

    @Test fun ring_again_restarts30s_noSecondAcquire() = runTest {
        val c = started()
        ring()
        advanceTimeBy(20_000); runCurrent()
        ring()
        assertEquals(listOf(CAM), acquired)
        assertEquals(now(), c.ui.value.doorbell!!.ringAtMs)
        advanceTimeBy(20_000); runCurrent() // 40 s after the first ring, 20 s after the last
        assertNotNull(c.ui.value.doorbell)
        advanceTimeBy(10_001); runCurrent()
        assertNull(c.ui.value.doorbell)
    }

    @Test fun `ringType otherThanRing onDoorbellEntity doesNothing`() = runTest {
        val c = started()
        fire(BELL, "motion")
        assertNull(c.ui.value.doorbell)
        assertTrue(c.ui.value.banners.isEmpty())
        assertTrue(acquired.isEmpty())
    }

    @Test fun autoClose_after30s_releasesFeed() = runTest {
        val c = started()
        ring()
        advanceTimeBy(ALERT_SHOW_MS - 1); runCurrent()
        assertNotNull(c.ui.value.doorbell)
        assertTrue(released.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertNull(c.ui.value.doorbell)
        assertEquals(listOf(CAM), released)
    }

    @Test fun captureKeys_falseAfterAutoClose() = runTest {
        val c = started()
        assertFalse(c.capturesKeys.value)
        ring()
        assertTrue(c.capturesKeys.value)
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertFalse(c.capturesKeys.value)
    }

    @Test fun expand_pausesMedia_noAutoClose() = runTest {
        val c = started()
        ring()
        c.expand()
        assertTrue(c.ui.value.doorbell!!.expanded)
        assertEquals(1, media.pauses)
        advanceTimeBy(ALERT_SHOW_MS + 10_000); runCurrent()
        assertNotNull(c.ui.value.doorbell)
        assertTrue(released.isEmpty())
    }

    @Test fun `expand noMediaPlaying nothingPausedOrResumed`() = runTest {
        media.isPlaying = false
        val c = started()
        ring()
        c.expand()
        c.dismiss()
        assertEquals(0, media.pauses)
        assertEquals(0, media.plays)
    }

    @Test fun expand_safetyCloseAt3min_resumes() = runTest {
        val c = started()
        ring()
        c.expand()
        advanceTimeBy(EXPANDED_SAFETY_MS - 1); runCurrent()
        assertNotNull(c.ui.value.doorbell)
        advanceTimeBy(2); runCurrent()
        assertNull(c.ui.value.doorbell)
        assertEquals(1, media.plays)
        assertEquals(listOf(CAM), released)
    }

    @Test fun ringWhileExpanded_refreshesOnly() = runTest {
        val c = started()
        ring()
        c.expand()
        advanceTimeBy(60_000); runCurrent()
        ring()
        val d = c.ui.value.doorbell!!
        assertTrue(d.expanded)
        assertEquals(now(), d.ringAtMs)
        assertEquals(listOf(CAM), acquired)
        assertEquals(1, media.pauses)
    }

    @Test fun dismiss_fromExpanded_release_resume() = runTest {
        val c = started()
        ring()
        c.expand()
        c.dismiss()
        assertNull(c.ui.value.doorbell)
        assertFalse(c.capturesKeys.value)
        assertEquals(listOf(CAM), released)
        assertEquals(1, media.plays)
        // The cancelled safety timer never fires a second close.
        advanceTimeBy(EXPANDED_SAFETY_MS + 1); runCurrent()
        assertEquals(listOf(CAM), released)
        assertEquals(1, media.plays)
    }

    @Test fun dismiss_small_neverTouchesMedia() = runTest {
        val c = started()
        ring()
        c.dismiss()
        assertEquals(0, media.pauses)
        assertEquals(0, media.plays)
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertEquals(listOf(CAM), released)
    }

    @Test fun tvOff_dropsAndStartsNoFeed() = runTest {
        tvOn = false
        val c = started()
        ring()
        fire(WASHER, "washing_is_complete")
        assertNull(c.ui.value.doorbell)
        assertTrue(c.ui.value.banners.isEmpty())
        assertTrue(acquired.isEmpty())
        // Dropped, not queued: the TV coming on later shows nothing.
        tvOn = true
        advanceTimeBy(1_000); runCurrent()
        assertNull(c.ui.value.doorbell)
    }

    @Test fun banner_washerDone_30sThenGone() = runTest {
        val c = started()
        fire(WASHER, "washing_is_complete")
        val b = c.ui.value.banners.single()
        assertEquals(WASHER, b.key)
        assertEquals(BannerText("🧺", "Washer done", "Laundry Room"), b.text)
        assertEquals(now(), b.shownAtMs)
        assertFalse(c.capturesKeys.value)
        advanceTimeBy(ALERT_SHOW_MS - 1); runCurrent()
        assertEquals(1, c.ui.value.banners.size)
        advanceTimeBy(2); runCurrent()
        assertTrue(c.ui.value.banners.isEmpty())
    }

    @Test fun banner_configOverride() = runTest {
        val c = AlertCenter(
            port, { AlertsConfig(mapOf("washing_is_complete" to "Laundry ready")) }, backgroundScope, { now() },
            { tvOn }, pauser, { acquired += it }, { released += it },
        )
        c.start(); runCurrent(); initial()
        fire(WASHER, "washing_is_complete")
        assertEquals("Laundry ready", c.ui.value.banners.single().text.text)
    }

    @Test fun `banner sameEntityAgain restartsNotDuplicates`() = runTest {
        val c = started()
        fire(WASHER, "washing_is_complete")
        advanceTimeBy(20_000); runCurrent()
        fire(WASHER, "error_during_washing")
        val b = c.ui.value.banners.single()
        assertEquals("Washer problem", b.text.text)
        assertEquals(now(), b.shownAtMs)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, c.ui.value.banners.size)
        advanceTimeBy(10_001); runCurrent()
        assertTrue(c.ui.value.banners.isEmpty())
    }

    @Test fun banners_max3_newestFirst() = runTest {
        // Four distinct sources: the doorbell without camera fires as a doorbell, so add a 4th banner entity.
        port.entities = j(port.entities.jsonArray.toString().dropLast(1) +
            """,{"entity_id":"event.oven_notification","device_id":"d_other","labels":["lbl_alert"]}]""")
        val c = started()
        port.emit("""{"a":{"event.oven_notification":{"s":"${ts(-3_600_000)}","a":{"event_type":"preheat_done","event_types":["preheat_done"]}}}}""")
        runCurrent()
        fire(WASHER, "washing_is_complete"); advanceTimeBy(1_000); runCurrent()
        fire(DRYER, "drying_is_complete"); advanceTimeBy(1_000); runCurrent()
        fire(OTHER, "door_open"); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(OTHER, DRYER, WASHER), c.ui.value.banners.map { it.key })
        fire("event.oven_notification", "preheat_done")
        assertEquals(MAX_BANNERS, c.ui.value.banners.size)
        assertEquals(listOf("event.oven_notification", OTHER, DRYER), c.ui.value.banners.map { it.key })
        assertEquals("Fridge · Preheat done", c.ui.value.banners.first().text.text)
        // The dropped washer's timer can't remove anything else later; the rest expire on their own clocks.
        advanceTimeBy(ALERT_SHOW_MS - 3_000 + 1); runCurrent() // washer's original 30 s mark
        assertEquals(3, c.ui.value.banners.size)
        advanceTimeBy(1_000); runCurrent() // dryer's 30 s
        assertEquals(listOf("event.oven_notification", OTHER), c.ui.value.banners.map { it.key })
    }

    @Test fun reconnect_resetsTracker_noReplay() = runTest {
        val c = started()
        fire(WASHER, "washing_is_complete")
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertTrue(c.ui.value.banners.isEmpty())
        val recent = ts(-5_000) // newer than anything remembered and within 60 s: would fire without the reset
        port.connectionCount.value = 2
        runCurrent()
        assertEquals(2, port.subs.size)
        assertTrue(port.subs[0].closed)
        port.emit("""{"a":{
            "$BELL":{"s":"$recent","a":{"event_type":"ring","event_types":["ring"]}},
            "$WASHER":{"s":"$recent","a":{"event_type":"washing_is_complete","event_types":["washing_is_complete"]}}
        }}""")
        runCurrent()
        assertNull(c.ui.value.doorbell)
        assertTrue(c.ui.value.banners.isEmpty())
        assertTrue(acquired.isEmpty())
        // A genuinely new event after the reconnect still fires.
        fire(WASHER, "washing_is_complete")
        assertEquals(1, c.ui.value.banners.size)
    }

    @Test fun `notConnectedAtStart noLoadAttempt thenLoadsAfterAuth`() = runTest {
        port.status.value = HaStatus.CONNECTING
        port.connectionCount.value = 0
        val c = AlertCenter(
            port, { AlertsConfig() }, backgroundScope, { now() }, { tvOn }, pauser,
            { acquired += it }, { released += it },
        )
        c.start()
        runCurrent()
        assertTrue(port.requests.isEmpty())
        assertTrue(port.subs.isEmpty())
        port.status.value = HaStatus.CONNECTED
        port.connectionCount.value = 1
        runCurrent()
        assertEquals(4, port.requests.size)
        assertEquals(1, port.subs.size)
    }

    @Test fun `doorbellWithoutCamera showsLabelOnly noAcquire`() = runTest {
        val c = started()
        fire(BARE_BELL, "ring")
        val d = c.ui.value.doorbell!!
        assertNull(d.cameraId)
        assertEquals("Side Bell", d.name)
        assertTrue(acquired.isEmpty())
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertNull(c.ui.value.doorbell)
        assertTrue(released.isEmpty())
    }

    // --- Every close path releases the feed exactly once (a leaked hold = a camera streaming forever). ---

    @Test fun releaseOnEveryClosePath_dismissSmall() = runTest {
        val c = started()
        ring()
        c.dismiss()
        c.dismiss() // a second Back is harmless
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
    }

    @Test fun releaseOnEveryClosePath_dismissExpanded() = runTest {
        val c = started()
        ring()
        c.expand()
        c.dismiss()
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
    }

    @Test fun releaseOnEveryClosePath_autoClose30s() = runTest {
        started()
        ring()
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
    }

    @Test fun releaseOnEveryClosePath_safetyClose3min() = runTest {
        val c = started()
        ring()
        c.expand()
        advanceTimeBy(EXPANDED_SAFETY_MS + 1); runCurrent()
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
    }

    @Test fun `releaseOnEveryClosePath stopWhileShowing`() = runTest {
        val c = started()
        ring()
        c.expand()
        c.stop()
        assertNull(c.ui.value.doorbell)
        assertFalse(c.capturesKeys.value)
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
        assertEquals(1, media.plays)
        runCurrent()
        assertTrue(port.subs.last().closed)
        advanceTimeBy(EXPANDED_SAFETY_MS + 1); runCurrent()
        assertBalanced()
    }

    @Test fun `releaseOnEveryClosePath reconnectSourceGone`() = runTest {
        val c = started()
        ring()
        port.entities = j("""[{"entity_id":"$WASHER","device_id":"d_washer","labels":["lbl_alert"]}]""")
        port.connectionCount.value = 2
        runCurrent()
        assertNull(c.ui.value.doorbell)
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertBalanced()
    }

    @Test fun `reconnect sourceStillThere keepsDoorbell`() = runTest {
        val c = started()
        ring()
        port.connectionCount.value = 2
        runCurrent()
        assertNotNull(c.ui.value.doorbell)
        assertTrue(released.isEmpty())
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertNull(c.ui.value.doorbell)
        assertBalanced()
    }

    @Test fun stop_clearsBanners_andRestartWorks() = runTest {
        val c = started()
        fire(WASHER, "washing_is_complete")
        c.stop()
        assertTrue(c.ui.value.banners.isEmpty())
        c.start(); runCurrent(); initial()
        fire(DRYER, "drying_is_complete")
        assertEquals(listOf(DRYER), c.ui.value.banners.map { it.key })
    }

    // --- TV turning off is a close path (no resume onto a dark TV). ---

    @Test fun releaseOnEveryClosePath_tvOff() = runTest {
        val c = started()
        ring()
        c.onTvOff()
        assertNull(c.ui.value.doorbell)
        assertFalse(c.capturesKeys.value)
        assertEquals(listOf(CAM), acquired)
        assertBalanced()
        c.onTvOff() // repeated is harmless
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertBalanced()
    }

    @Test fun tvOff_whileExpanded_forgetsNoResume() = runTest {
        val c = started()
        ring()
        c.expand()
        assertEquals(1, media.pauses)
        c.onTvOff()
        assertNull(c.ui.value.doorbell)
        assertEquals(0, media.plays)
        advanceTimeBy(EXPANDED_SAFETY_MS + 1); runCurrent()
        assertEquals(0, media.plays)
        assertBalanced()
        // Forgotten: a later close of a new small pop-up doesn't resume the old pause either.
        ring()
        c.dismiss()
        assertEquals(0, media.plays)
    }

    @Test fun tvOff_clearsBanners() = runTest {
        val c = started()
        fire(WASHER, "washing_is_complete")
        fire(DRYER, "drying_is_complete")
        assertEquals(2, c.ui.value.banners.size)
        c.onTvOff()
        assertTrue(c.ui.value.banners.isEmpty())
        advanceTimeBy(ALERT_SHOW_MS + 1); runCurrent()
        assertTrue(c.ui.value.banners.isEmpty())
    }

    @Test fun oddRegistryJson_noCrash_noAlerts() = runTest {
        port.throwOnRegistry = true
        val c = AlertCenter(
            port, { AlertsConfig() }, backgroundScope, { now() }, { tvOn }, pauser,
            { acquired += it }, { released += it },
        )
        c.start()
        runCurrent()
        assertNull(c.ui.value.doorbell)
        assertTrue(c.ui.value.banners.isEmpty())
        assertTrue(port.subs.isEmpty())
    }
}

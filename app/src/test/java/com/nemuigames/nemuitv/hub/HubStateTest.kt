package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun j(s: String) = Json.parseToJsonElement(s)
private fun obj(s: String) = j(s).jsonObject

class FakeSub : HaSubscription {
    val channel = Channel<JsonObject>(Channel.UNLIMITED)
    var closed = false
    override val events: Flow<JsonObject> = channel.receiveAsFlow()
    override fun close() { closed = true; channel.close() }
}

data class Call(val domain: String, val service: String, val data: JsonObject, val target: JsonObject?)

class FakePort : HaPort {
    override val status = MutableStateFlow(HaStatus.CONNECTED)
    override val connectionCount = MutableStateFlow(1)
    val requests = mutableListOf<String>()
    val subs = mutableListOf<FakeSub>()
    val subPayloads = mutableListOf<JsonObject>()
    val calls = mutableListOf<Call>()
    var callResult: HaResult = HaResult.Ok(null)
    /** How long (virtual time) each call waits before answering [callResult], like HA waiting on a sleeping car. */
    var callDelayMs = 0L
    /** The request timeout each call was given. */
    val callTimeouts = mutableListOf<Long>()
    /** When set, every registry request waits for it (to see whether the requests overlap). */
    var requestGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** When true the registries also hold two scripts labeled for the hub plus a guest-mode flag. */
    var withScenes = false
    /** When true the registries also hold a second car (other_car) and both cars' climate + overheat entities. */
    var withCars = false

    val registries = mapOf(
        "config/area_registry/list" to j("""[{"area_id":"upstairs","name":"Upstairs"},{"area_id":"front_yard","name":"Front Yard"}]"""),
        "config/device_registry/list" to j("""[{"id":"d_lock","area_id":"front_yard","name":"Front Door","manufacturer":"August"},
            {"id":"d_gm","area_id":null,"name":"My Car","manufacturer":"Tesla"}]"""),
        "config/entity_registry/list" to j("""[
            {"entity_id":"light.floor_2","area_id":"upstairs","name":"Floor 2","labels":[]},
            {"entity_id":"switch.plug","area_id":"upstairs","name":"Plug","labels":[]},
            {"entity_id":"light.night","area_id":"upstairs","name":"Night","labels":["keep_on"]},
            {"entity_id":"light.dead","area_id":"upstairs","name":"Dead","labels":[]},
            {"entity_id":"lock.front_door","device_id":"d_lock","labels":[]},
            {"entity_id":"sensor.front_door_battery","device_id":"d_lock","labels":[]},
            {"entity_id":"climate.house","area_id":"upstairs","name":"House","labels":[]},
            {"entity_id":"person.alex","labels":[]},
            {"entity_id":"sensor.my_car_battery_level","device_id":"d_gm","labels":[]}
        ]"""),
        "config/label_registry/list" to j("""[{"label_id":"keep_on","name":"keep_on"}]"""),
    )

    override suspend fun request(type: String, payload: JsonObject, timeoutMs: Long): HaResult {
        requests += type
        requestGate?.await()
        if (status.value != HaStatus.CONNECTED) return HaResult.NotConnected
        if (withScenes && type == "config/entity_registry/list") {
            val extra = j("""[
                {"entity_id":"script.night_mode","labels":["nemuitv"]},
                {"entity_id":"script.guest_mode","labels":["nemuitv"]},
                {"entity_id":"input_boolean.guest_mode","labels":[]}
            ]""").jsonArray
            return HaResult.Ok(JsonArray(registries[type]!!.jsonArray + extra))
        }
        if (withCars && type == "config/device_registry/list") {
            val extra = j("""[{"id":"d_oc","area_id":null,"name":"Other Car","manufacturer":"Tesla"}]""").jsonArray
            return HaResult.Ok(JsonArray(registries[type]!!.jsonArray + extra))
        }
        if (withCars && type == "config/entity_registry/list") {
            val extra = j("""[
                {"entity_id":"sensor.other_car_battery_level","device_id":"d_oc","labels":[]},
                {"entity_id":"climate.my_car_climate","device_id":"d_gm","labels":[]},
                {"entity_id":"climate.my_car_cabin_overheat_protection","device_id":"d_gm","labels":[]},
                {"entity_id":"climate.other_car_climate","device_id":"d_oc","labels":[]},
                {"entity_id":"climate.other_car_cabin_overheat_protection","device_id":"d_oc","labels":[]}
            ]""").jsonArray
            return HaResult.Ok(JsonArray(registries[type]!!.jsonArray + extra))
        }
        if (withScenes && type == "config/label_registry/list") {
            return HaResult.Ok(j("""[{"label_id":"keep_on","name":"keep_on"},{"label_id":"nemuitv","name":"nemuitv"}]"""))
        }
        return registries[type]?.let { HaResult.Ok(it) } ?: HaResult.Error(com.nemuigames.nemuitv.ha.HaError("unknown", type))
    }

    override suspend fun subscribe(type: String, payload: JsonObject): HaSubscription? {
        if (status.value != HaStatus.CONNECTED) return null
        subPayloads += payload
        return FakeSub().also { subs += it }
    }

    override suspend fun callServiceForResult(
        domain: String, service: String, data: JsonObject, target: JsonObject?, timeoutMs: Long,
    ): HaResult {
        calls += Call(domain, service, data, target)
        callTimeouts += timeoutMs
        if (callDelayMs > 0) kotlinx.coroutines.delay(callDelayMs)
        return callResult
    }

    fun emit(event: String) { subs.last().channel.trySend(obj(event)) }
}

private const val INITIAL = """{"a":{
    "light.floor_2":{"s":"off","a":{"supported_color_modes":["brightness"],"friendly_name":"Floor 2"}},
    "switch.plug":{"s":"on","a":{}},
    "light.night":{"s":"on","a":{"supported_color_modes":["onoff"]}},
    "light.dead":{"s":"unavailable","a":{}},
    "lock.front_door":{"s":"locked","a":{}},
    "person.alex":{"s":"home","a":{}}
}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class HubStateTest {
    private val port = FakePort()

    /** The hub never unlocks or opens anything: checked after every test in this class. */
    @After fun noUnlockEver() {
        val bad = port.calls.filter { it.service == "unlock" || it.service == "open" }
        assertTrue("forbidden calls: $bad", bad.isEmpty())
    }

    private fun TestScope.opened(): HubState {
        val hub = HubState(port, { HubConfig(peopleNames = mapOf("person.alex" to "Alex")) }, backgroundScope)
        hub.open()
        runCurrent()
        port.emit(INITIAL)
        runCurrent()
        return hub
    }

    private fun HubState.upstairs() = ui.value.model!!.rooms.first { it.areaId == "upstairs" }
    private fun HubState.tile(id: String) = tiles(upstairs()).first { it.tile.entityId == id }

    @Test fun open_loadsModelAndStates() = runTest {
        val hub = HubState(port, { HubConfig(peopleNames = mapOf("person.alex" to "Alex")) }, backgroundScope)
        assertNull(hub.ui.value.model)
        hub.open()
        runCurrent()
        assertEquals(
            listOf("config/area_registry/list", "config/device_registry/list", "config/entity_registry/list", "config/label_registry/list"),
            port.requests,
        )
        assertTrue(hub.ui.value.loading)
        val ids = port.subPayloads.single()["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "light.floor_2", "switch.plug", "light.night", "light.dead", "lock.front_door", "sensor.front_door_battery",
                "climate.house", "person.alex", "sensor.my_car_battery_level", "sensor.my_car_charging",
                "sensor.my_car_battery_range", "sensor.my_car_odometer",
                "sensor.my_car_tire_pressure_front_left", "sensor.my_car_tire_pressure_front_right",
                "sensor.my_car_tire_pressure_rear_left", "sensor.my_car_tire_pressure_rear_right",
                "binary_sensor.my_car_status", "sensor.my_car_inside_temperature", "sensor.my_car_outside_temperature",
            ),
            ids,
        )
        port.emit(INITIAL)
        runCurrent()
        val ui = hub.ui.value
        assertFalse(ui.loading)
        assertFalse(ui.offline)
        assertEquals("on", ui.states["switch.plug"]!!.state)
        assertEquals("Locked", ui.strip!!.lock)
        assertEquals("Alex", ui.strip.people)
        assertEquals(1, ui.strip.lightsOn)
        val floor = hub.tile("light.floor_2")
        assertFalse(floor.on); assertTrue(floor.dimmable); assertFalse(floor.pending); assertNull(floor.brightness)
        assertTrue(hub.tile("light.dead").unavailable)
        hub.open() // already open: nothing new
        runCurrent()
        assertEquals(4, port.requests.size)
        assertEquals(1, port.subs.size)
    }

    @Test fun stateEvent_updatesTile() = runTest {
        val hub = opened()
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"on","a":{"brightness":102}}}}}""")
        runCurrent()
        val t = hub.tile("light.floor_2")
        assertTrue(t.on)
        assertEquals(40, t.brightness)
        assertTrue(t.dimmable) // kept from the full state
        port.emit("""{"c":{"light.floor_2":{"-":{"a":["supported_color_modes"]}}}}""")
        runCurrent()
        assertFalse(hub.tile("light.floor_2").dimmable)
        assertTrue(hub.tile("light.floor_2").on) // "s" absent = unchanged
        assertEquals(2, hub.ui.value.strip!!.lightsOn)
    }

    @Test fun toggle_confirmedByEvent_clearsPending() = runTest {
        val hub = opened()
        hub.toggle("light.floor_2")
        runCurrent()
        assertEquals(Call("light", "turn_on", JsonObject(emptyMap()), obj("""{"entity_id":"light.floor_2"}""")), port.calls.single())
        assertTrue(hub.tile("light.floor_2").pending)
        assertFalse(hub.tile("light.floor_2").on) // never optimistic
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"on"}}}}""")
        runCurrent()
        assertFalse(hub.tile("light.floor_2").pending)
        assertTrue(hub.tile("light.floor_2").on)
        advanceTimeBy(5_000); runCurrent()
        assertNull(hub.ui.value.message)

        hub.toggle("switch.plug")
        runCurrent()
        assertEquals("turn_off", port.calls.last().service)
        assertEquals("switch", port.calls.last().domain)
    }

    @Test fun toggle_noConfirm3s_revertsWithMessage() = runTest {
        val hub = opened()
        hub.toggle("light.floor_2")
        runCurrent()
        advanceTimeBy(2_999); runCurrent()
        assertTrue(hub.tile("light.floor_2").pending)
        assertNull(hub.ui.value.message)
        advanceTimeBy(2); runCurrent()
        assertFalse(hub.tile("light.floor_2").pending)
        assertFalse(hub.tile("light.floor_2").on)
        assertEquals("Couldn't reach Floor 2", hub.ui.value.message)
        advanceTimeBy(3_998); runCurrent() // message set at t=3000, now t=6999
        assertEquals("Couldn't reach Floor 2", hub.ui.value.message)
        advanceTimeBy(2); runCurrent()
        assertNull(hub.ui.value.message)
    }

    @Test fun message_newerMessageRestartsClock() = runTest {
        val hub = opened()
        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        hub.toggle("light.floor_2")
        advanceTimeBy(3_000); runCurrent()
        hub.toggle("light.floor_2")
        advanceTimeBy(3_999); runCurrent()
        assertEquals("Home Assistant offline", hub.ui.value.message)
        advanceTimeBy(2); runCurrent()
        assertNull(hub.ui.value.message)
    }

    @Test fun reconnect_firstEventReplacesStates() = runTest {
        val hub = opened()
        port.connectionCount.value = 2
        runCurrent()
        port.emit("""{"a":{"switch.plug":{"s":"on","a":{}}}}""")
        runCurrent()
        assertNull(hub.ui.value.states["lock.front_door"]) // not listed by the new subscription = gone
        assertTrue(hub.tile("light.floor_2").unavailable)
    }

    @Test fun toggle_callFails_revertsImmediately() = runTest {
        val hub = opened()
        port.callResult = HaResult.Timeout
        hub.toggle("light.floor_2")
        runCurrent()
        assertFalse(hub.tile("light.floor_2").pending)
        assertEquals("Couldn't reach Floor 2", hub.ui.value.message)
    }

    @Test fun `toggle light timeoutResult stillFinishesRightAway`() = runTest {
        val hub = opened()
        port.callDelayMs = 1_000
        port.callResult = HaResult.Timeout
        hub.toggle("light.floor_2")
        runCurrent()
        assertEquals(listOf(CONFIRM_WINDOW_MS + REPLY_GRACE_MS), port.callTimeouts)
        assertTrue(hub.tile("light.floor_2").pending)
        advanceTimeBy(1_001); runCurrent()
        assertFalse(hub.tile("light.floor_2").pending) // before the 3 s window: a non-car timeout ends it
        assertEquals("Couldn't reach Floor 2", hub.ui.value.message)
    }

    @Test fun toggle_whilePending_ignoresSecondPress() = runTest {
        val hub = opened()
        hub.toggle("light.floor_2")
        runCurrent()
        hub.toggle("light.floor_2")
        hub.setBrightness("light.floor_2", 50)
        runCurrent()
        assertEquals(1, port.calls.size)
    }

    @Test fun offline_commandsRejected_noQueue() = runTest {
        val hub = opened()
        // Socket dropped between status updates: the call itself reports NotConnected.
        port.callResult = HaResult.NotConnected
        hub.toggle("light.floor_2")
        runCurrent()
        assertEquals(1, port.calls.size)
        assertFalse(hub.tile("light.floor_2").pending)
        assertEquals("Home Assistant offline", hub.ui.value.message)

        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        assertTrue(hub.ui.value.offline)
        hub.toggle("switch.plug")
        hub.roomOff(hub.upstairs())
        runCurrent()
        assertEquals(1, port.calls.size) // nothing sent while offline
        assertEquals("Home Assistant offline", hub.ui.value.message)
        assertFalse(hub.tile("switch.plug").pending)

        port.callResult = HaResult.Ok(null)
        port.status.value = HaStatus.CONNECTED
        port.connectionCount.value = 2
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertFalse(hub.ui.value.offline)
        assertEquals(1, port.calls.size) // and nothing replayed after reconnect
    }

    @Test fun reconnect_reloadsRegistries() = runTest {
        val hub = opened()
        assertEquals(4, port.requests.size)
        port.connectionCount.value = 2
        runCurrent()
        assertEquals(8, port.requests.size)
        assertEquals(2, port.subs.size)
        assertTrue(port.subs[0].closed)
        assertFalse(port.subs[1].closed)
        port.emit("""{"a":{"switch.plug":{"s":"off","a":{}}}}""")
        runCurrent()
        assertFalse(hub.tile("switch.plug").on)
    }

    @Test fun roomOff_sendsOneTurnOff_withTargets() = runTest {
        val hub = opened()
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"on"}}}}""")
        runCurrent()
        hub.roomOff(hub.upstairs())
        runCurrent()
        val call = port.calls.single()
        assertEquals("homeassistant", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals(
            listOf("light.floor_2", "switch.plug"),
            (call.target!!["entity_id"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue(hub.tile("light.floor_2").pending)
        assertTrue(hub.tile("switch.plug").pending)
        assertFalse(hub.tile("light.night").pending)
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"off"}}}}""")
        runCurrent()
        assertFalse(hub.tile("light.floor_2").pending)
        assertTrue(hub.tile("switch.plug").pending)
        advanceTimeBy(3_001); runCurrent()
        assertFalse(hub.tile("switch.plug").pending)
        assertEquals("Couldn't reach Upstairs", hub.ui.value.message)
    }

    @Test fun roomOff_allConfirmed_noMessage() = runTest {
        val hub = opened()
        hub.roomOff(hub.upstairs())
        runCurrent()
        port.emit("""{"c":{"switch.plug":{"+":{"s":"off"}},"light.floor_2":{"+":{"s":"off"}}}}""")
        runCurrent()
        assertFalse(hub.tiles(hub.upstairs()).any { it.pending })
        advanceTimeBy(5_000); runCurrent()
        assertNull(hub.ui.value.message)
    }

    @Test fun brightness_save_sendsOneCall() = runTest {
        val hub = opened()
        hub.setBrightness("light.floor_2", 40)
        runCurrent()
        val call = port.calls.single()
        assertEquals(Call("light", "turn_on", obj("""{"brightness_pct":40}"""), obj("""{"entity_id":"light.floor_2"}""")), call)
        assertTrue(hub.tile("light.floor_2").pending)
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"on","a":{"brightness":100}}}}}""") // 39%, within ±2
        runCurrent()
        assertFalse(hub.tile("light.floor_2").pending)
        assertEquals(39, hub.tile("light.floor_2").brightness)
        advanceTimeBy(5_000); runCurrent()
        assertNull(hub.ui.value.message)
    }

    @Test fun brightness_offlineSave_noQueue_message() = runTest {
        val hub = opened()
        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        hub.setBrightness("light.floor_2", 40)
        runCurrent()
        assertTrue(port.calls.isEmpty())
        assertEquals("Home Assistant offline", hub.ui.value.message)
        port.status.value = HaStatus.CONNECTED
        port.connectionCount.value = 2
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(port.calls.isEmpty())
    }

    @Test fun removedEntity_dropsTile() = runTest {
        val hub = opened()
        port.emit("""{"r":["switch.plug"]}""")
        runCurrent()
        assertFalse(hub.tiles(hub.upstairs()).any { it.tile.entityId == "switch.plug" })
        assertNull(hub.ui.value.states["switch.plug"])
        assertEquals(3, hub.tiles(hub.upstairs()).size)
    }

    @Test fun close_unsubscribes() = runTest {
        val hub = opened()
        hub.toggle("light.floor_2")
        runCurrent()
        hub.close()
        runCurrent()
        assertTrue(port.subs.single().closed)
        advanceTimeBy(5_000); runCurrent()
        assertNull(hub.ui.value.message) // pending timer cancelled
        assertFalse(hub.tile("light.floor_2").pending)
        port.connectionCount.value = 2
        runCurrent()
        assertEquals(4, port.requests.size) // no reload while closed
        hub.open()
        runCurrent()
        assertEquals(8, port.requests.size)
        assertEquals(2, port.subs.size)
    }
    @Test fun `open notLiveUntilFirstEvent noStripFromEmptyStates`() = runTest {
        val hub = HubState(port, { HubConfig(peopleNames = mapOf("person.alex" to "Alex")) }, backgroundScope)
        assertFalse(hub.ui.value.live)
        hub.open()
        runCurrent()
        assertTrue(hub.ui.value.model != null)
        assertEquals(1, port.subs.size)
        assertFalse(hub.ui.value.live)
        assertNull(hub.ui.value.strip) // no "Nobody home" / "Lock unavailable" before HA has said anything
        port.emit(INITIAL)
        runCurrent()
        assertTrue(hub.ui.value.live)
        assertEquals("Locked", hub.ui.value.strip!!.lock)
        port.emit("""{"c":{"light.floor_2":{"+":{"s":"on"}}}}""")
        runCurrent()
        assertTrue(hub.ui.value.live)
    }

    @Test fun `reopen staleStatesNotLive commandsRejectedNoCall`() = runTest {
        val hub = opened()
        assertTrue(hub.ui.value.live)
        hub.close()
        runCurrent()
        assertFalse(hub.ui.value.live)
        hub.open()
        runCurrent()
        assertEquals(2, port.subs.size)
        assertFalse(hub.ui.value.live) // last session's states are still there, but not confirmed yet
        hub.toggle("light.floor_2")
        hub.roomOff(hub.upstairs())
        hub.setBrightness("light.floor_2", 40)
        runCurrent()
        assertTrue(port.calls.isEmpty())
        assertTrue(hub.ui.value.pending.isEmpty())
        port.emit(INITIAL)
        runCurrent()
        assertTrue(hub.ui.value.live)
        hub.toggle("light.floor_2")
        runCurrent()
        assertEquals(1, port.calls.size)
    }

    @Test fun `statusLeavesConnected notLiveUntilNewFirstEvent`() = runTest {
        val hub = opened()
        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        assertFalse(hub.ui.value.live)
        port.status.value = HaStatus.CONNECTED
        port.connectionCount.value = 2
        runCurrent()
        assertFalse(hub.ui.value.live)
        hub.toggle("light.floor_2")
        runCurrent()
        assertTrue(port.calls.isEmpty())
        port.emit(INITIAL)
        runCurrent()
        assertTrue(hub.ui.value.live)
    }

    @Test fun `reconnectWithoutStatusDrop notLiveUntilNewFirstEvent`() = runTest {
        val hub = opened()
        port.connectionCount.value = 2
        runCurrent()
        assertFalse(hub.ui.value.live)
        port.emit(INITIAL)
        runCurrent()
        assertTrue(hub.ui.value.live)
    }

    @Test fun open_registryRequestsRunInParallel() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        port.requestGate = gate
        val hub = HubState(port, { HubConfig() }, backgroundScope)
        hub.open()
        runCurrent()
        assertEquals(4, port.requests.size) // all four out before any answer
        gate.complete(Unit)
        runCurrent()
        assertTrue(hub.ui.value.model != null)
    }

    @Test fun `subscribedIds includeScenesFlagsAndCarDetails`() {
        val m = HubModel(
            rooms = emptyList(), scenes = listOf("script.guest_mode", "script.night_mode"), cameras = emptyList(),
            thermostat = null, locks = emptyList(), lockBatteries = emptyList(), people = emptyList(),
            cars = listOf("second_car"), sceneFlags = mapOf("script.guest_mode" to "input_boolean.guest_mode"),
        )
        val ids = subscribedIds(m)
        val expected = listOf(
            "script.guest_mode", "script.night_mode", "input_boolean.guest_mode",
            "sensor.second_car_battery_level", "sensor.second_car_charging", "sensor.second_car_battery_range", "sensor.second_car_odometer",
            "binary_sensor.second_car_status", "sensor.second_car_inside_temperature", "sensor.second_car_outside_temperature",
        ) + TIRE_POSITIONS.map { "sensor.second_car_tire_pressure_$it" }
        assertEquals(expected.sorted(), ids.sorted())
        assertEquals(ids.distinct(), ids)
    }

    @Test fun `subscribedIds includeCarClimateAndCopWhenPresent`() {
        val m = HubModel(
            rooms = emptyList(), scenes = emptyList(), cameras = emptyList(), thermostat = null, locks = emptyList(),
            lockBatteries = emptyList(), people = emptyList(), cars = listOf("my_car", "other_car"),
            carClimate = mapOf("my_car" to "climate.my_car_climate"),
            carCop = mapOf("other_car" to "climate.other_car_cabin_overheat_protection"),
        )
        val ids = subscribedIds(m)
        assertTrue("climate.my_car_climate" in ids)
        assertTrue("climate.other_car_cabin_overheat_protection" in ids)
        assertFalse("climate.other_car_climate" in ids)
        assertFalse("climate.my_car_cabin_overheat_protection" in ids)
        assertTrue("binary_sensor.other_car_status" in ids)
    }

    // ---- 1.1.0: car climate + cabin overheat protection ----

    private val NO_KEY = "Command failed: Vehicle did not recognize the key used to authorize command. Make sure your key is paired with the vehicle"
    private val CAR_STATES = """{"a":{
        "climate.my_car_climate":{"s":"off","a":{}},
        "climate.my_car_cabin_overheat_protection":{"s":"off","a":{}},
        "climate.other_car_climate":{"s":"heat_cool","a":{}},
        "climate.other_car_cabin_overheat_protection":{"s":"cool","a":{}}
    }}"""
    private var nowMs = 1_000_000L

    private fun TestScope.openedWithCars(): HubState {
        port.withCars = true
        val hub = HubState(port, { HubConfig() }, backgroundScope, clock = { nowMs })
        hub.open(); runCurrent()
        port.emit(INITIAL); runCurrent()
        port.emit(CAR_STATES); runCurrent()
        return hub
    }

    private fun target(c: Call) = c.target!!["entity_id"]!!.jsonPrimitive.content
    private fun noKeyMessage(name: String) = "$name isn't connected for controls yet. Pair its key in the Tesla app."

    @Test fun `carModel climateAndCopFromRegistry`() = runTest {
        val hub = openedWithCars()
        val m = hub.ui.value.model!!
        assertEquals(mapOf("my_car" to "climate.my_car_climate", "other_car" to "climate.other_car_climate"), m.carClimate)
        assertEquals("climate.house", m.thermostat)
        assertTrue("climate.my_car_cabin_overheat_protection" in port.subPayloads.last()["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `carClimate offSendsTurnOn onSendsTurnOff`() = runTest {
        val hub = openedWithCars()
        hub.toggleCarClimate("my_car")
        hub.toggleCarClimate("other_car")
        runCurrent()
        assertEquals(2, port.calls.size)
        assertEquals(Call("climate", "turn_on", JsonObject(emptyMap()), port.calls[0].target), port.calls[0])
        assertEquals("climate.my_car_climate", target(port.calls[0]))
        assertEquals("turn_off", port.calls[1].service)
        assertEquals("climate.other_car_climate", target(port.calls[1]))
        assertTrue("climate.my_car_climate" in hub.ui.value.pending)
        port.emit(diff("climate.my_car_climate", "heat_cool")); runCurrent()
        assertFalse("climate.my_car_climate" in hub.ui.value.pending)
        assertTrue("climate.other_car_climate" in hub.ui.value.pending)
    }

    @Test fun `cop setsNextHvacMode confirmedByThatMode`() = runTest {
        val hub = openedWithCars()
        hub.toggleCop("my_car")
        hub.toggleCop("other_car")
        runCurrent()
        assertEquals("set_hvac_mode", port.calls[0].service)
        assertEquals("climate.my_car_cabin_overheat_protection", target(port.calls[0]))
        assertEquals("cool", port.calls[0].data["hvac_mode"]!!.jsonPrimitive.content)
        assertEquals("off", port.calls[1].data["hvac_mode"]!!.jsonPrimitive.content)
        port.emit(diff("climate.my_car_cabin_overheat_protection", "fan_only")); runCurrent()
        assertTrue("climate.my_car_cabin_overheat_protection" in hub.ui.value.pending) // not the asked mode
        port.emit(diff("climate.my_car_cabin_overheat_protection", "cool")); runCurrent()
        assertFalse("climate.my_car_cabin_overheat_protection" in hub.ui.value.pending)
    }

    @Test fun `carClimate asleepCar pending45sThenCouldntReach notBlocked`() = runTest {
        val hub = openedWithCars()
        hub.toggleCarClimate("my_car")
        runCurrent()
        advanceTimeBy(44_999); runCurrent()
        assertTrue("climate.my_car_climate" in hub.ui.value.pending)
        assertNull(hub.ui.value.message)
        advanceTimeBy(2); runCurrent()
        assertFalse("climate.my_car_climate" in hub.ui.value.pending)
        assertEquals("Couldn't reach My Car's climate", hub.ui.value.message)
        assertTrue(hub.ui.value.blockedCars.isEmpty())
    }

    @Test fun `carClimate noKeyReplyAfter20s stillBlocks noCouldntReach`() = runTest {
        val hub = openedWithCars()
        port.callDelayMs = 20_000 // asleep car: Tesla tries to wake it before refusing the unpaired key
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(listOf(CAR_CONFIRM_MS + REPLY_GRACE_MS), port.callTimeouts)
        advanceTimeBy(19_999); runCurrent()
        assertTrue("climate.my_car_climate" in hub.ui.value.pending)
        assertNull(hub.ui.value.message)
        advanceTimeBy(2); runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)
        assertEquals(noKeyMessage("My Car"), hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())
        advanceTimeBy(CAR_CONFIRM_MS); runCurrent() // the window's end must not add "Couldn't reach"
        assertFalse(hub.ui.value.message?.startsWith("Couldn't") == true)
    }

    @Test fun `carClimate timeoutAt10s staysPending stateAt25sConfirms`() = runTest {
        val hub = openedWithCars()
        port.callDelayMs = 10_000
        port.callResult = HaResult.Timeout
        hub.toggleCarClimate("my_car")
        runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertTrue("climate.my_car_climate" in hub.ui.value.pending) // the timeout didn't end the command
        assertNull(hub.ui.value.message)
        advanceTimeBy(15_000); runCurrent()
        port.emit(diff("climate.my_car_climate", "heat_cool")); runCurrent()
        assertFalse("climate.my_car_climate" in hub.ui.value.pending)
        assertNull(hub.ui.value.message)
        advanceTimeBy(CAR_CONFIRM_MS); runCurrent()
        assertNull(hub.ui.value.message)
    }

    @Test fun `cop timeout staysPending untilWindowEnds`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Timeout
        hub.toggleCop("my_car")
        runCurrent()
        assertTrue("climate.my_car_cabin_overheat_protection" in hub.ui.value.pending)
        advanceTimeBy(CAR_CONFIRM_MS + 1); runCurrent()
        assertTrue(hub.ui.value.pending.isEmpty())
        assertEquals("Couldn't reach My Car's overheat protection", hub.ui.value.message)
    }

    @Test fun `cop otherError couldntMessage notBlocked`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", "Command failed: vehicle unavailable"))
        hub.toggleCop("my_car")
        runCurrent()
        assertEquals("Couldn't reach My Car's overheat protection", hub.ui.value.message)
        assertTrue(hub.ui.value.blockedCars.isEmpty())
    }

    @Test fun `noKeyError blocksThatCarOnly`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCop("my_car")
        runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)
        assertEquals(noKeyMessage("My Car"), hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())

        port.callResult = HaResult.Ok(null)
        advanceTimeBy(MESSAGE_MS + 1); runCurrent()
        assertNull(hub.ui.value.message)
        hub.toggleCarClimate("my_car")
        hub.toggleCop("my_car")
        runCurrent()
        assertEquals(1, port.calls.size) // nothing sent while blocked
        assertEquals(noKeyMessage("My Car"), hub.ui.value.message)

        hub.toggleCarClimate("other_car")
        runCurrent()
        assertEquals(2, port.calls.size)
        assertEquals("climate.other_car_climate", target(port.calls[1]))
    }

    @Test fun `noKeyBlock clearsOnReconnect`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)

        port.callResult = HaResult.Ok(null)
        port.connectionCount.value = 2
        runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars) // not live yet: still blocked
        port.emit(INITIAL); port.emit(CAR_STATES); runCurrent()
        assertTrue(hub.ui.value.blockedCars.isEmpty())
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(2, port.calls.size)
    }

    @Test fun `noKeyBlock clearsAfter30Minutes`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCarClimate("my_car")
        runCurrent()
        port.callResult = HaResult.Ok(null)

        nowMs += 30 * 60_000 - 1
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(1, port.calls.size)
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)

        nowMs += 1
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(2, port.calls.size)
        assertTrue(hub.ui.value.blockedCars.isEmpty())
    }

    @Test fun `noKeyBlock grayLookClearsOnTimeWithoutPress`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCarClimate("my_car")
        runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)

        advanceTimeBy(CAR_BLOCK_MS - 1); runCurrent()
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)
        advanceTimeBy(1); runCurrent()
        assertTrue(hub.ui.value.blockedCars.isEmpty())
        assertEquals(1, port.calls.size) // cleared by the timer, not by a press
    }

    @Test fun `noKeyBlock close stopsItsTimer`() = runTest {
        val hub = openedWithCars()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("home_assistant_error", NO_KEY))
        hub.toggleCarClimate("my_car")
        runCurrent()
        hub.close()
        runCurrent()
        val afterClose = hub.ui.value
        advanceTimeBy(CAR_BLOCK_MS + 1); runCurrent()
        // The timer died with close(): nothing touches the closed hub's state.
        assertEquals(setOf("my_car"), hub.ui.value.blockedCars)
        assertEquals(afterClose.blockedCars, hub.ui.value.blockedCars)
    }

    @Test fun `carControls whilePending ignored`() = runTest {
        val hub = openedWithCars()
        hub.toggleCarClimate("my_car")
        hub.toggleCop("my_car")
        runCurrent()
        hub.toggleCarClimate("my_car")
        hub.toggleCop("my_car")
        runCurrent()
        assertEquals(2, port.calls.size)
    }

    @Test fun `carControls offline noCall`() = runTest {
        val hub = openedWithCars()
        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        hub.toggleCarClimate("my_car")
        hub.toggleCop("my_car")
        runCurrent()
        assertTrue(port.calls.isEmpty())
        assertEquals(OFFLINE_MESSAGE, hub.ui.value.message)
    }

    @Test fun `carControls unknownCar noCall`() = runTest {
        val hub = openedWithCars()
        hub.toggleCarClimate("no_car")
        hub.toggleCop("no_car")
        runCurrent()
        assertTrue(port.calls.isEmpty())
    }

    // ---- Task 4: scenes, lock, thermostat ----

    private val SCENE_STATES = """{"a":{
        "script.night_mode":{"s":"off","a":{"friendly_name":"Night mode"}},
        "script.guest_mode":{"s":"off","a":{"friendly_name":"Guest mode"}},
        "input_boolean.guest_mode":{"s":"off","a":{}}
    }}"""
    private fun diff(id: String, state: String, attrs: String = "{}") = """{"c":{"$id":{"+":{"s":"$state","a":$attrs}}}}"""

    private fun TestScope.openedWithScenes(): HubState {
        port.withScenes = true
        val hub = HubState(port, { HubConfig() }, backgroundScope)
        hub.open(); runCurrent()
        port.emit(INITIAL); runCurrent()
        port.emit(SCENE_STATES); runCurrent()
        return hub
    }

    @Test fun scene_sendsScriptTurnOn() = runTest {
        val hub = openedWithScenes()
        hub.runScene("script.night_mode")
        runCurrent()
        val c = port.calls.single()
        assertEquals("script", c.domain); assertEquals("turn_on", c.service)
        assertEquals(obj("""{"entity_id":"script.night_mode"}"""), c.target)
        assertEquals(obj("{}"), c.data)
    }

    @Test fun scene_okResult_clearsPendingNoMessage() = runTest {
        val hub = openedWithScenes()
        hub.runScene("script.night_mode")
        runCurrent()
        assertTrue(hub.ui.value.pending.isEmpty())
        assertNull(hub.ui.value.message)
        advanceTimeBy(CONFIRM_WINDOW_MS + 100)
        assertNull(hub.ui.value.message)
    }

    @Test fun scene_errorResult_message() = runTest {
        val hub = openedWithScenes()
        port.callResult = HaResult.Error(com.nemuigames.nemuitv.ha.HaError("x", "y"))
        hub.runScene("script.night_mode")
        runCurrent()
        assertEquals("Couldn't run Night mode", hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())
    }

    @Test fun scene_whilePending_ignored() = runTest {
        val hub = openedWithScenes()
        port.callResult = HaResult.Timeout // never confirms by state either
        hub.runScene("script.night_mode")
        hub.runScene("script.night_mode") // before the first coroutine ran: still pending
        runCurrent()
        assertEquals(1, port.calls.size)
    }

    @Test fun scene_offline_noQueue() = runTest {
        val hub = openedWithScenes()
        port.status.value = HaStatus.DISCONNECTED
        runCurrent()
        hub.runScene("script.night_mode")
        runCurrent()
        assertEquals(OFFLINE_MESSAGE, hub.ui.value.message)
        port.status.value = HaStatus.CONNECTED
        port.connectionCount.value = 2
        runCurrent()
        port.emit(INITIAL); runCurrent()
        assertTrue(port.calls.isEmpty())
    }

    @Test fun scene_notConnectedResult_offlineMessage() = runTest {
        val hub = openedWithScenes()
        port.callResult = HaResult.NotConnected
        hub.runScene("script.night_mode")
        runCurrent()
        assertEquals(OFFLINE_MESSAGE, hub.ui.value.message)
    }

    @Test fun lock_alreadyLocked_noCall() = runTest {
        val hub = opened()
        hub.lock("lock.front_door")
        runCurrent()
        assertTrue(port.calls.isEmpty())
        assertNull(hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())
    }

    @Test fun lock_sendsLockLock_neverUnlock() = runTest {
        val hub = opened()
        port.emit(diff("lock.front_door", "unlocked")); runCurrent()
        hub.lock("lock.front_door")
        runCurrent()
        val c = port.calls.single()
        assertEquals("lock", c.domain); assertEquals("lock", c.service)
        assertEquals(obj("""{"entity_id":"lock.front_door"}"""), c.target)
        assertTrue("lock.front_door" in hub.ui.value.pending)
        // the @After check asserts no unlock/open call across the whole class
    }

    @Test fun lock_slowBolt_confirmedAfter10s() = runTest {
        val hub = opened()
        port.emit(diff("lock.front_door", "unlocked")); runCurrent()
        hub.lock("lock.front_door")
        advanceTimeBy(1_000); port.emit(diff("lock.front_door", "locking")); runCurrent()
        assertTrue("lock.front_door" in hub.ui.value.pending)
        advanceTimeBy(9_000); port.emit(diff("lock.front_door", "locked")); runCurrent()
        assertTrue(hub.ui.value.pending.isEmpty())
        advanceTimeBy(LOCK_CONFIRM_MS)
        assertNull(hub.ui.value.message)
    }

    @Test fun lock_noConfirm20s_reverts() = runTest {
        val hub = opened()
        port.emit(diff("lock.front_door", "unlocked")); runCurrent()
        hub.lock("lock.front_door")
        advanceTimeBy(LOCK_CONFIRM_MS - 100)
        assertNull(hub.ui.value.message)
        assertTrue("lock.front_door" in hub.ui.value.pending)
        advanceTimeBy(200)
        assertEquals("Couldn't lock Front door", hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())
    }

    private val COOL_ATTRS = """{"hvac_modes":["cool","heat","off"],"temperature":72.0,"current_temperature":74.0,"min_temp":50,"max_temp":90}"""
    private fun TestScope.openedWithClimate(): Pair<HubState, ThermoDraft> {
        val hub = opened()
        port.emit(diff("climate.house", "cool", COOL_ATTRS)); runCurrent()
        return hub to startDraft(hub.ui.value.states["climate.house"])!!
    }

    @Test fun climate_unchanged_noCall() = runTest {
        val (hub, start) = openedWithClimate()
        hub.setClimate("climate.house", start, start)
        runCurrent()
        assertTrue(port.calls.isEmpty())
        assertTrue(hub.ui.value.pending.isEmpty())
    }

    @Test fun climate_oneCall_confirmedByState() = runTest {
        val (hub, start) = openedWithClimate()
        hub.setClimate("climate.house", start, start.copy(target = 69))
        runCurrent()
        val c = port.calls.single()
        assertEquals("climate", c.domain); assertEquals("set_temperature", c.service)
        assertEquals(obj("""{"hvac_mode":"cool","temperature":69}"""), c.data)
        assertEquals(obj("""{"entity_id":"climate.house"}"""), c.target)
        assertTrue("climate.house" in hub.ui.value.pending)
        port.emit(diff("climate.house", "cool", COOL_ATTRS.replace("72.0", "69.0"))); runCurrent()
        assertTrue(hub.ui.value.pending.isEmpty())
        assertNull(hub.ui.value.message)
    }

    @Test fun climate_noConfirm_message() = runTest {
        val (hub, start) = openedWithClimate()
        hub.setClimate("climate.house", start, start.copy(target = 69))
        advanceTimeBy(CONFIRM_WINDOW_MS + 100)
        assertEquals("Couldn't reach Thermostat", hub.ui.value.message)
        assertTrue(hub.ui.value.pending.isEmpty())
    }

    @Test fun scenes_flagOnFromBoolean() = runTest {
        val hub = openedWithScenes()
        var list = hub.scenes()
        assertEquals(listOf("script.guest_mode", "script.night_mode"), list.map { it.entityId }.sorted())
        assertTrue(list.none { it.flagOn || it.pending })
        val night = list.first { it.entityId == "script.night_mode" }
        assertEquals("Night mode", night.name); assertEquals("💤", night.icon)
        port.emit(diff("input_boolean.guest_mode", "on")); runCurrent()
        list = hub.scenes()
        assertTrue(list.first { it.entityId == "script.guest_mode" }.flagOn)
        assertFalse(list.first { it.entityId == "script.night_mode" }.flagOn)
    }
}

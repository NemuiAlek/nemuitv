package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import com.nemuigames.nemuitv.ha.HaPort
import com.nemuigames.nemuitv.ha.HaResult
import com.nemuigames.nemuitv.ha.HaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

data class TileUi(
    val tile: Tile, val on: Boolean, val unavailable: Boolean, val brightness: Int?, val dimmable: Boolean, val pending: Boolean,
    /** A running appliance cycle on this tile's device (washer, dryer…): time left + bar. */
    val cycle: CycleProgress? = null,
)

data class HubUi(
    val loading: Boolean, val offline: Boolean, val model: HubModel?, val states: Map<String, EntityState>,
    val strip: Strip?, val message: String?,   // message = "Couldn't reach Floor 2", clears after 4 s
    /** Entity ids with a command waiting for HA's confirmation (part of the state so the UI recomposes). */
    val pending: Set<String> = emptySet(),
    /**
     * True once HA's first event on the current subscription has arrived; false on open/close and whenever the
     * connection drops. While false the shown states may be old (last session's), so the UI dims them and commands
     * are refused, like offline but without the offline banner.
     */
    val live: Boolean = false,
)

const val CONFIRM_WINDOW_MS = 3_000L
const val LOCK_CONFIRM_MS = 20_000L
const val MESSAGE_MS = 4_000L
const val OFFLINE_MESSAGE = "Home Assistant offline"

/** The model's ids the hub needs live state for (no cameras). */
fun subscribedIds(model: HubModel): List<String> = buildList {
    model.rooms.forEach { r -> r.tiles.forEach { add(it.entityId) } }
    addAll(model.locks)
    addAll(model.lockBatteries)
    model.thermostat?.let { add(it) }
    addAll(model.people)
    addAll(model.scenes)
    addAll(model.sceneFlags.values)
    model.cycles.forEach { c -> add(c.remaining); c.total?.let { add(it) }; c.status?.let { add(it) } }
    model.cars.forEach { p ->
        add("sensor.${p}_battery_level"); add("sensor.${p}_charging")
        add("sensor.${p}_battery_range"); add("sensor.${p}_odometer")
        TIRE_POSITIONS.forEach { pos -> add("sensor.${p}_tire_pressure_$pos") }
    }
}.distinct()

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.epochMs(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.let { (it * 1000).toLong() }

/** Applies one compact `subscribe_entities` event ("a" full, "c" diff, "r" removed) to [states]. */
fun applyEntityEvent(states: Map<String, EntityState>, event: JsonObject): Map<String, EntityState> {
    val out = states.toMutableMap()
    (event["a"] as? JsonObject)?.forEach { (id, v) ->
        val o = v as? JsonObject ?: return@forEach
        out[id] = EntityState(o.str("s") ?: "unknown", o["a"] as? JsonObject ?: JsonObject(emptyMap()), o.epochMs("lc"))
    }
    (event["c"] as? JsonObject)?.forEach { (id, v) ->
        val o = v as? JsonObject ?: return@forEach
        val old = out[id] ?: EntityState("unknown", JsonObject(emptyMap()))
        val plus = o["+"] as? JsonObject
        val minus = ((o["-"] as? JsonObject)?.get("a") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
        val attrs = LinkedHashMap<String, JsonElement>(old.attributes)
        (plus?.get("a") as? JsonObject)?.let { attrs.putAll(it) }
        minus.forEach { attrs.remove(it) }
        out[id] = EntityState(plus?.str("s") ?: old.state, JsonObject(attrs), plus?.epochMs("lc") ?: old.lastChangedMs)
    }
    (event["r"] as? JsonArray)?.forEach { e -> (e as? JsonPrimitive)?.contentOrNull?.let { out.remove(it) } }
    return out
}

private fun removedIds(event: JsonObject): Set<String> =
    (event["r"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()

/**
 * The ⌂ Home hub's state holder: loads the HA model, keeps live entity states, sends commands.
 * The TV only shows what HA confirms: a command marks its tiles pending until a state event shows the expected
 * state, else after [CONFIRM_WINDOW_MS] the pending mark clears and a message names what couldn't be reached.
 * Nothing is ever queued while offline.
 *
 * Not thread-safe: call it and run [scope] on one thread (Main in the app, the test dispatcher in tests).
 */
class HubState(
    private val port: HaPort,
    private val cfg: () -> HubConfig,
    private val scope: CoroutineScope,
) {
    private class Command(val ids: MutableSet<String>, val failName: String, val expected: (EntityState?) -> Boolean) {
        var timer: Job? = null
    }

    private val _ui = MutableStateFlow(
        HubUi(loading = false, offline = port.status.value != HaStatus.CONNECTED, model = null, states = emptyMap(), strip = null, message = null)
    )
    val ui: StateFlow<HubUi> = _ui.asStateFlow()

    private var openJob: Job? = null
    private var messageJob: Job? = null
    private val commands = mutableListOf<Command>()

    fun open() {
        if (openJob != null) return
        _ui.update { it.copy(live = false) }
        openJob = scope.launch {
            launch {
                port.status.collect { s ->
                    val connected = s == HaStatus.CONNECTED
                    // Back to CONNECTED doesn't make the states live: only the new subscription's first event does.
                    _ui.update { it.copy(offline = !connected, live = it.live && connected) }
                }
            }
            // StateFlow replays the current count, so this is the first load too; collectLatest cancels the
            // previous load/subscription (its finally closes the old subscription) on every reconnect.
            port.connectionCount.collectLatest { loadAndListen() }
        }
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        commands.toList().forEach { it.timer?.cancel() }
        commands.clear()
        _ui.update { it.copy(loading = false, pending = emptySet(), live = false) }
    }

    private suspend fun loadAndListen() {
        // A new connection = a new subscription: nothing is confirmed until its first event.
        _ui.update { it.copy(live = false, loading = it.loading || it.model == null) }
        val results = coroutineScope {
            listOf(
                "config/area_registry/list", "config/device_registry/list",
                "config/entity_registry/list", "config/label_registry/list",
            ).map { async { port.request(it) } }.awaitAll()
        }
        if (results.any { it !is HaResult.Ok }) {
            _ui.update { it.copy(loading = false) }
            return
        }
        val (areas, devices, entities, labels) = results.map { (it as HaResult.Ok).result }
        val model = buildHubModel(parseRegistries(areas, devices, entities, labels), cfg())
        // The strip waits for the first event: built from no / old states it would claim "Nobody home" etc.
        _ui.update { it.copy(model = model) }

        val payload = JsonObject(mapOf("entity_ids" to JsonArray(subscribedIds(model).map { JsonPrimitive(it) })))
        val sub = port.subscribe("subscribe_entities", payload)
        if (sub == null) {
            _ui.update { it.copy(loading = false) }
            return
        }
        var first = true
        try {
            // HA's first event lists every existing id, so it replaces (not merges onto) the previous states.
            sub.events.collect { onEvent(it, replace = first); first = false }
        } finally {
            sub.close()
        }
    }

    private fun onEvent(event: JsonObject, replace: Boolean) {
        val gone = removedIds(event)
        _ui.update { u ->
            val states = applyEntityEvent(if (replace) emptyMap() else u.states, event)
            val model = u.model?.let { m ->
                if (gone.isEmpty()) m
                else m.copy(rooms = m.rooms.map { r -> r.copy(tiles = r.tiles.filter { it.entityId !in gone }) })
            }
            u.copy(
                loading = false, model = model, states = states, strip = model?.let { buildStrip(it, states, cfg()) },
                live = u.live || (replace && port.status.value == HaStatus.CONNECTED),
            )
        }
        checkCommands()
    }

    /** Clears confirmed ids; a command whose ids are all confirmed is done (its timer cancelled). */
    private fun checkCommands() {
        val states = _ui.value.states
        commands.toList().forEach { c ->
            c.ids.removeAll { c.expected(states[it]) }
            if (c.ids.isEmpty()) finish(c, null)
        }
        publishPending()
    }

    private fun finish(c: Command, message: String?) {
        c.timer?.cancel()
        commands.remove(c)
        publishPending()
        message?.let { showMessage(it) }
    }

    private fun publishPending() {
        val ids = commands.flatMap { it.ids }.toSet()
        if (ids != _ui.value.pending) _ui.update { it.copy(pending = ids) }
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        _ui.update { it.copy(message = text) }
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            _ui.update { if (it.message == text) it.copy(message = null) else it }
        }
    }

    private fun findTile(entityId: String): Tile? =
        _ui.value.model?.rooms?.firstNotNullOfOrNull { r -> r.tiles.firstOrNull { it.entityId == entityId } }

    private fun currentRoom(room: Room): Room = _ui.value.model?.rooms?.firstOrNull { it.areaId == room.areaId } ?: room

    fun tiles(room: Room, nowMs: Long = System.currentTimeMillis()): List<TileUi> {
        val u = _ui.value
        val cycles = u.model?.cycles.orEmpty().associateBy { it.tileId }
        return currentRoom(room).tiles.map { t ->
            val s = u.states[t.entityId]
            TileUi(t, isOn(s), isUnavailable(s), brightnessPercent(s), isDimmable(s), t.entityId in u.pending,
                cycles[t.entityId]?.let { progressOf(it, u.states, nowMs) })
        }
    }

    /** The cycles running now (washer, dryer…), in room-tile order, for the Overview. */
    fun runningCycles(nowMs: Long = System.currentTimeMillis()): List<Pair<Cycle, CycleProgress>> {
        val u = _ui.value
        return u.model?.cycles.orEmpty().mapNotNull { c -> progressOf(c, u.states, nowMs)?.let { c to it } }
    }

    private fun progressOf(c: Cycle, states: Map<String, EntityState>, nowMs: Long): CycleProgress? =
        cycleProgress(c.status?.let { states[it] }, states[c.remaining], c.total?.let { states[it] }, nowMs)

    /**
     * True when commands can't go out right now (nothing is kept for later). Offline shows the message; connected
     * but not [HubUi.live] yet (states unconfirmed, the UI shows them dimmed) is refused quietly.
     */
    private fun rejectOffline(): Boolean {
        if (port.status.value != HaStatus.CONNECTED) { showMessage(OFFLINE_MESSAGE); return true }
        return !_ui.value.live
    }

    private fun send(
        ids: List<String>, failName: String, expected: (EntityState?) -> Boolean,
        domain: String, service: String, data: JsonObject, target: JsonObject,
        windowMs: Long = CONFIRM_WINDOW_MS, confirmOnResult: Boolean = false, failVerb: String = "reach",
    ) {
        val c = Command(ids.toMutableSet(), failName, expected)
        commands += c
        c.timer = scope.launch {
            delay(windowMs)
            c.timer = null
            if (c in commands) finish(c, "Couldn't $failVerb $failName")
        }
        checkCommands() // ids already in the expected state are confirmed right away
        scope.launch {
            val r = port.callServiceForResult(domain, service, data, target)
            if (c !in commands) return@launch
            if (r is HaResult.Ok) {
                // Scripts give no reliable state change: HA's success reply is their confirmation.
                if (confirmOnResult) finish(c, null)
            } else {
                finish(c, if (r is HaResult.NotConnected) OFFLINE_MESSAGE else "Couldn't $failVerb $failName")
            }
        }
    }

    private fun entityTarget(id: String) = JsonObject(mapOf("entity_id" to JsonPrimitive(id)))

    fun toggle(entityId: String) {
        if (rejectOffline()) return
        if (entityId in _ui.value.pending) return
        val tile = findTile(entityId) ?: return
        val wasOn = isOn(_ui.value.states[entityId])
        send(
            listOf(entityId), tile.name, { isOn(it) != wasOn && !isUnavailable(it) },
            tile.domain, if (wasOn) "turn_off" else "turn_on", JsonObject(emptyMap()), entityTarget(entityId),
        )
    }

    fun roomOff(room: Room) {
        if (rejectOffline()) return
        val r = currentRoom(room)
        val targets = roomOffTargets(r, _ui.value.states)
        if (targets.isEmpty() || targets.any { it in _ui.value.pending }) return
        send(
            targets, r.name, { it?.state == "off" },
            "homeassistant", "turn_off", JsonObject(emptyMap()),
            JsonObject(mapOf("entity_id" to JsonArray(targets.map { JsonPrimitive(it) }))),
        )
    }

    fun setBrightness(entityId: String, percent: Int) {
        if (rejectOffline()) return
        if (entityId in _ui.value.pending) return
        val tile = findTile(entityId)?.takeIf { it.domain == "light" } ?: return
        send(
            listOf(entityId), tile.name,
            { s -> isOn(s) && brightnessPercent(s)?.let { abs(it - percent) <= 2 } == true },
            "light", "turn_on", JsonObject(mapOf("brightness_pct" to JsonPrimitive(percent))), entityTarget(entityId),
        )
    }

    fun scenes(): List<SceneUi> {
        val u = _ui.value
        val m = u.model ?: return emptyList()
        return m.scenes.map { id ->
            SceneUi(
                id, sceneIcon(id), sceneName(id, u.states[id]),
                flagOn = m.sceneFlags[id]?.let { isOn(u.states[it]) } ?: false,
                pending = id in u.pending,
            )
        }
    }

    fun runScene(scriptId: String) {
        if (rejectOffline()) return
        if (scriptId in _ui.value.pending) return
        send(
            listOf(scriptId), sceneName(scriptId, _ui.value.states[scriptId]), { false },
            "script", "turn_on", JsonObject(emptyMap()), entityTarget(scriptId),
            confirmOnResult = true, failVerb = "run",
        )
    }

    /** Lock only: nothing in the hub ever calls unlock or open. */
    fun lock(lockId: String) {
        if (rejectOffline()) return
        if (lockId in _ui.value.pending) return
        val s = _ui.value.states[lockId]
        if (s?.state == "locked" || isUnavailable(s)) return
        val name = (s?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull
            ?: lockId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
        send(
            listOf(lockId), name, { it?.state == "locked" },
            "lock", "lock", JsonObject(emptyMap()), entityTarget(lockId),
            windowMs = LOCK_CONFIRM_MS, failVerb = "lock",
        )
    }

    fun setClimate(entityId: String, start: ThermoDraft, end: ThermoDraft) {
        val call = climateCall(start, end) ?: return
        if (rejectOffline()) return
        if (entityId in _ui.value.pending) return
        send(
            listOf(entityId), "Thermostat", { climateConfirmed(end, it) },
            "climate", call.service, call.data, entityTarget(entityId),
        )
    }
}

package com.nemuigames.nemuitv.alerts

import android.util.Log
import com.nemuigames.nemuitv.config.AlertsConfig
import com.nemuigames.nemuitv.ha.HaPort
import com.nemuigames.nemuitv.ha.HaResult
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.hub.EntityState
import com.nemuigames.nemuitv.hub.applyEntityEvent
import com.nemuigames.nemuitv.hub.entityName
import com.nemuigames.nemuitv.hub.parseRegistries
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class DoorbellUi(val cameraId: String?, val name: String, val expanded: Boolean, val ringAtMs: Long)
data class BannerUi(val key: String, val text: BannerText, val shownAtMs: Long)   // key = entityId
data class AlertUi(val doorbell: DoorbellUi?, val banners: List<BannerUi>)        // newest banner first, max 3

const val ALERT_SHOW_MS = 30_000L
const val EXPANDED_SAFETY_MS = 180_000L
const val MAX_BANNERS = 3

/**
 * Watches the `nemuitv_alert` event entities (own `subscribe_entities` on the shared socket, whatever is on screen)
 * and holds the doorbell pop-up + banner stack with their timers.
 *
 * The doorbell's camera hold is balanced: [acquireFeed] once when a pop-up opens, [releaseFeed] once on every close
 * path (Back, 30 s auto-close, 3 min safety close, [stop], a reconnect that no longer lists the doorbell).
 *
 * Not thread-safe: call it and run [scope] on one thread (Main in the app, the test dispatcher in tests).
 */
class AlertCenter(
    private val port: HaPort,
    private val cfg: () -> AlertsConfig,
    private val scope: CoroutineScope,
    wallClock: () -> Long,
    private val tvOn: () -> Boolean,
    private val pauser: MediaPauser,
    private val acquireFeed: (String) -> Unit,
    private val releaseFeed: (String) -> Unit,
    /** `hub.excludedAreas`: a doorbell's camera in one of these areas is not used for its pop-up. */
    private val excludedAreas: () -> List<String> = { emptyList() },
) {
    /** The wall clock behind every [BannerUi.shownAtMs] / [DoorbellUi.ringAtMs] (the views time their countdown on it). */
    val clock: () -> Long = wallClock
    private val tracker = AlertTracker(wallClock)

    private val _ui = MutableStateFlow(AlertUi(null, emptyList()))
    val ui: StateFlow<AlertUi> = _ui.asStateFlow()

    private val _capturesKeys = MutableStateFlow(false)
    /** True iff a doorbell pop-up / expanded view shows (OK and Back are then captured). */
    val capturesKeys: StateFlow<Boolean> = _capturesKeys.asStateFlow()

    private var job: Job? = null
    private var sources: Map<String, AlertSource> = emptyMap()
    private var states: Map<String, EntityState> = emptyMap()

    /** The source of the doorbell showing now (null = none). Its camera hold, if any, is [heldCamera]. */
    private var doorbellSource: AlertSource? = null
    private var heldCamera: String? = null
    private var doorbellTimer: Job? = null
    private val bannerTimers = HashMap<String, Job>()

    private fun publish(ui: AlertUi) {
        _ui.value = ui
        _capturesKeys.value = ui.doorbell != null
    }

    fun start() {
        if (job != null) return
        // StateFlow replays the current count, so this is the first load too; collectLatest cancels the previous
        // load/subscription (its finally closes the old subscription) on every reconnect.
        job = scope.launch { port.connectionCount.collectLatest {
            // The replayed initial count (0) arrives before HA has authenticated: nothing to load yet. The next
            // emission (auth_ok, status already CONNECTED) does the real load.
            if (port.status.value == HaStatus.CONNECTED) loadAndListen()
        } }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeDoorbell()
        bannerTimers.values.forEach { it.cancel() }
        bannerTimers.clear()
        publish(AlertUi(null, emptyList()))
    }

    /** OK on the small pop-up: pause the playing media (if any), no auto-close, 3 min safety close. */
    fun expand() {
        val d = _ui.value.doorbell ?: return
        if (d.expanded) return
        pauser.pauseIfPlaying()
        publish(_ui.value.copy(doorbell = d.copy(expanded = true)))
        restartDoorbellTimer(EXPANDED_SAFETY_MS)
    }

    /** Back (small or expanded): closes the doorbell, releases its feed, resumes only what we paused. */
    fun dismiss() = closeDoorbell()

    /**
     * The TV turned off: close the doorbell like any close path (feed released once) but forget what we paused instead
     * of resuming it onto a dark TV, and drop every banner.
     */
    fun onTvOff() {
        closeDoorbell(resume = false)
        bannerTimers.values.forEach { it.cancel() }
        bannerTimers.clear()
        if (_ui.value.banners.isNotEmpty()) publish(_ui.value.copy(banners = emptyList()))
    }

    private fun closeDoorbell(resume: Boolean = true) {
        doorbellTimer?.cancel()
        doorbellTimer = null
        val wasShowing = _ui.value.doorbell != null
        doorbellSource = null
        if (wasShowing) publish(_ui.value.copy(doorbell = null))
        heldCamera?.let { heldCamera = null; releaseFeed(it) }
        if (wasShowing) { if (resume) pauser.resumeIfWePaused() else pauser.forget() }
    }

    private fun restartDoorbellTimer(ms: Long) {
        doorbellTimer?.cancel()
        doorbellTimer = scope.launch {
            delay(ms)
            doorbellTimer = null
            closeDoorbell()
        }
    }

    private suspend fun loadAndListen() {
        try {
            loadAndListenUnguarded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NemuiTV", "alerts: load/listen failed", e)
        }
    }

    private suspend fun loadAndListenUnguarded() {
        val results = coroutineScope {
            listOf(
                "config/area_registry/list", "config/device_registry/list",
                "config/entity_registry/list", "config/label_registry/list",
            ).map { async { port.request(it) } }.awaitAll()
        }
        if (results.any { it !is HaResult.Ok }) {
            Log.w("NemuiTV", "alerts: registry load failed; no alerts until the next reconnect")
            return
        }
        val (areas, devices, entities, labels) = results.map { (it as HaResult.Ok).result }
        val list = alertSources(parseRegistries(areas, devices, entities, labels), excludedAreas())
        sources = list.associateBy { it.entityId }
        // A doorbell showing from before the reconnect whose source (or camera) is gone: close it (frees the feed).
        doorbellSource?.let { d -> val fresh = sources[d.entityId]; if (fresh == null || fresh.cameraId != d.cameraId) closeDoorbell() }
        // No replays: everything seen first on this subscription is only remembered.
        tracker.reset()
        states = emptyMap()
        if (list.isEmpty()) return

        val ids = (list.map { it.entityId } + list.mapNotNull { it.cameraId }).distinct()
        val payload = JsonObject(mapOf("entity_ids" to JsonArray(ids.map { JsonPrimitive(it) })))
        val sub = port.subscribe("subscribe_entities", payload) ?: return
        Log.i("NemuiTV", "alerts: watching ${list.size} sources: ${list.joinToString(", ") { it.entityId }}")
        var first = true
        try {
            sub.events.collect { onEvent(it, replace = first); first = false }
        } finally {
            sub.close()
        }
    }

    private fun onEvent(event: JsonObject, replace: Boolean) {
        states = applyEntityEvent(if (replace) emptyMap() else states, event)
        val touched = buildSet {
            (event["a"] as? JsonObject)?.keys?.let { addAll(it) }
            (event["c"] as? JsonObject)?.keys?.let { addAll(it) }
        }
        touched.forEach { id ->
            val source = sources[id] ?: return@forEach
            val state = states[id]
            val type = tracker.onState(id, state) ?: return@forEach
            when (classify(source, state)) {
                AlertKind.DOORBELL -> if (type == "ring") onRing(source)
                AlertKind.BANNER -> onBanner(source, type)
            }
        }
    }

    /**
     * Debug hook (only called from the debug build's DebugAlertReceiver): fires `washer`, `dryer` or `doorbell` as if
     * HA had sent it, skipping the no-replay tracker and the TV-off check; everything after that (camera hold, timers,
     * stack rules) is the real path. Uses the real labelled source when it's loaded, a stand-in otherwise.
     */
    internal fun debugFire(kind: String) {
        Log.i("NemuiTV", "alerts: debug fire $kind")
        when (kind) {
            "doorbell" -> onRing(
                sources.values.firstOrNull { it.cameraId != null }
                    ?: AlertSource("event.front_yard_doorbell_chime", AlertKind.DOORBELL, "camera.front_yard_doorbell", "Front door", null),
                checkTv = false,
            )
            "washer" -> onBanner(
                sources["event.washer_notification"]
                    ?: AlertSource("event.washer_notification", AlertKind.BANNER, null, "Washer", "Laundry Room"),
                "washing_is_complete", checkTv = false,
            )
            "dryer" -> onBanner(
                sources["event.dryer_notification"]
                    ?: AlertSource("event.dryer_notification", AlertKind.BANNER, null, "Dryer", "Laundry Room"),
                "drying_is_complete", checkTv = false,
            )
            else -> Log.w("NemuiTV", "alerts: debug fire: unknown kind '$kind' (washer|dryer|doorbell)")
        }
    }

    private fun onRing(source: AlertSource, checkTv: Boolean = true) {
        if (checkTv && !tvOn()) { Log.i("NemuiTV", "alerts: ring dropped (TV off)"); return }
        val now = clock()
        val current = _ui.value.doorbell
        if (current == null) {
            doorbellSource = source
            source.cameraId?.let { heldCamera = it; acquireFeed(it) }
            val name = source.cameraId?.let { entityName(it, states[it]) }
                ?: source.deviceName ?: entityName(source.entityId, states[source.entityId])
            publish(_ui.value.copy(doorbell = DoorbellUi(source.cameraId, name, expanded = false, ringAtMs = now)))
            restartDoorbellTimer(ALERT_SHOW_MS)
        } else {
            // A ring while open: no second pop-up. Small = restart its 30 s; expanded = refresh only (safety keeps running).
            publish(_ui.value.copy(doorbell = current.copy(ringAtMs = now)))
            if (!current.expanded) restartDoorbellTimer(ALERT_SHOW_MS)
        }
    }

    private fun onBanner(source: AlertSource, type: String, checkTv: Boolean = true) {
        if (checkTv && !tvOn()) { Log.i("NemuiTV", "alerts: banner dropped (TV off)"); return }
        val key = source.entityId
        val banner = BannerUi(key, bannerText(type, source, cfg().text), clock())
        val stack = listOf(banner) + _ui.value.banners.filter { it.key != key }
        stack.drop(MAX_BANNERS).forEach { bannerTimers.remove(it.key)?.cancel() }
        publish(_ui.value.copy(banners = stack.take(MAX_BANNERS)))
        bannerTimers.remove(key)?.cancel()
        bannerTimers[key] = scope.launch {
            delay(ALERT_SHOW_MS)
            bannerTimers.remove(key)
            publish(_ui.value.copy(banners = _ui.value.banners.filter { it.key != key }))
        }
    }
}

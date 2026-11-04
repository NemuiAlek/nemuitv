package com.nemuigames.nemuitv.ha

import com.nemuigames.nemuitv.config.HaConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class HaStatus { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

/**
 * One long-lived WebSocket to Home Assistant. Logs in, subscribes to the weather entity (plus the TV and lofi's
 * Spotify entity when configured), and
 * reconnects on its own (backoff 1s→30s; 5 minutes after a rejected token).
 */
class HaClient(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** How long [subscribe] waits for HA's result frame. */
    private val subscribeTimeoutMs: Long = 10_000,
) : HaPort {
    private val _status = MutableStateFlow(HaStatus.NOT_CONFIGURED)
    override val status: StateFlow<HaStatus> = _status.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    override val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HaResult>>()
    internal val pendingCount: Int get() = pending.size
    private val subscriptions = ConcurrentHashMap<Int, Channel<JsonObject>>()

    /** Test seam: subscriptions currently registered (live or still waiting for HA's result). */
    internal fun openSubscriptionCount(): Int = subscriptions.size

    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)
    val weather: StateFlow<WeatherSnapshot?> = _weather.asStateFlow()

    private val _weatherUpdatedAt = MutableStateFlow<Long?>(null)
    val weatherUpdatedAt: StateFlow<Long?> = _weatherUpdatedAt.asStateFlow()

    /** When we lost the connection (null while connected, or before the first try). Drives "stale" weather. */
    private val _disconnectedSince = MutableStateFlow<Long?>(null)
    val disconnectedSince: StateFlow<Long?> = _disconnectedSince.asStateFlow()

    /** Why weather can't show even though we're connected (typo'd entity, rejected subscription). Null = fine. */
    private val _weatherProblem = MutableStateFlow<String?>(null)
    val weatherProblem: StateFlow<String?> = _weatherProblem.asStateFlow()

    private val _tvState = MutableStateFlow<TvState?>(null)
    val tvState: StateFlow<TvState?> = _tvState.asStateFlow()

    /** State string of the Spotify entity (lofi's `spotifyEntity`, e.g. "playing"); null = unknown / not watched. */
    private val _spotifyState = MutableStateFlow<String?>(null)
    val spotifyState: StateFlow<String?> = _spotifyState.asStateFlow()

    /** The TV entity currently being watched (null = none). Changes with every [start]. */
    private val _tvEntity = MutableStateFlow<String?>(null)
    val tvEntity: StateFlow<String?> = _tvEntity.asStateFlow()

    private var config: HaConfig? = null
    private var tvEntityId: String? = null
    private var spotifyEntityId: String? = null
    private var job: Job? = null

    /** The live, authenticated socket; null whenever we are not CONNECTED. Commands are never queued. */
    @Volatile private var liveSocket: WebSocket? = null
    private val nextId = AtomicInteger(1)

    /**
     * Fire a Home Assistant service call on the live socket. Returns false (and sends nothing, queues nothing)
     * when not connected; true means the frame was handed to the socket, not that HA executed it.
     */
    fun callService(domain: String, service: String, data: JsonObject): Boolean {
        val ws = liveSocket ?: return false
        if (_status.value != HaStatus.CONNECTED) return false
        return ws.send(buildJsonObject {
            put("id", nextId.getAndIncrement()); put("type", "call_service")
            put("domain", domain); put("service", service)
            put("service_data", data as JsonElement)
        }.toString())
    }

    override suspend fun request(type: String, payload: JsonObject, timeoutMs: Long): HaResult {
        val ws = liveSocket ?: return HaResult.NotConnected
        if (_status.value != HaStatus.CONNECTED) return HaResult.NotConnected
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<HaResult>()
        pending[id] = deferred
        // The socket may have ended between the check above and the registration (its cleanup already ran).
        if (liveSocket !== ws || !ws.send(frame(id, type, payload))) {
            pending.remove(id)
            return HaResult.NotConnected
        }
        // finally: also drops the entry when the caller is cancelled while waiting (else it leaks until disconnect).
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: HaResult.Timeout
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun subscribe(type: String, payload: JsonObject): HaSubscription? {
        val ws = liveSocket ?: return null
        if (_status.value != HaStatus.CONNECTED) return null
        val id = nextId.getAndIncrement()
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
        val deferred = CompletableDeferred<HaResult>()
        // Registered before sending so events that follow the result right away are not lost.
        subscriptions[id] = channel
        pending[id] = deferred
        fun abort(): HaSubscription? { pending.remove(id); subscriptions.remove(id); channel.close(); return null }
        if (liveSocket !== ws || !ws.send(frame(id, type, payload))) return abort()
        val result = try {
            withTimeoutOrNull(subscribeTimeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            // The caller gave up while HA was still answering: HA may subscribe anyway, so tell it to stop.
            abort(); unsubscribe(id); throw e
        }
        if (result !is HaResult.Ok) {
            abort()
            // A late "success" would leave a subscription running on HA's side (an error reply to this is harmless).
            if (result == null) unsubscribe(id)
            return null
        }
        return object : HaSubscription {
            override val events = channel.receiveAsFlow()
            override fun close() {
                if (subscriptions.remove(id) == null) return
                channel.close()
                unsubscribe(id)
            }
        }
    }

    /** Asks HA to end subscription [subId] if we're still connected (nothing to do otherwise: the socket is gone). */
    private fun unsubscribe(subId: Int) {
        val live = liveSocket ?: return
        if (_status.value != HaStatus.CONNECTED) return
        live.send(buildJsonObject {
            put("id", nextId.getAndIncrement()); put("type", "unsubscribe_events"); put("subscription", subId)
        }.toString())
    }

    override suspend fun callServiceForResult(domain: String, service: String, data: JsonObject, target: JsonObject?): HaResult =
        request("call_service", buildJsonObject {
            put("domain", domain); put("service", service)
            put("service_data", data as JsonElement)
            if (target != null) put("target", target as JsonElement)
        })

    private fun frame(id: Int, type: String, payload: JsonObject) = buildJsonObject {
        payload.forEach { (k, v) -> put(k, v) }
        put("id", id); put("type", type) // last, so a payload can't overwrite them
    }.toString()

    /** Same config → nothing happens. Different → drop the old connection and connect to the new one. Null → stop. */
    @Synchronized
    fun start(config: HaConfig?, tvEntity: String? = null, spotifyEntity: String? = null) {
        if (config == this.config && tvEntity == this.tvEntityId && spotifyEntity == this.spotifyEntityId &&
            (config == null || job?.isActive == true)) return
        job?.cancel()
        liveSocket = null
        this.config = config
        this.tvEntityId = tvEntity
        this.spotifyEntityId = spotifyEntity
        _tvEntity.value = tvEntity
        _tvState.value = null
        _spotifyState.value = null
        _weather.value = null
        _weatherUpdatedAt.value = null
        _disconnectedSince.value = null
        _weatherProblem.value = null
        if (config == null) { _status.value = HaStatus.NOT_CONFIGURED; job = null; return }
        _status.value = HaStatus.CONNECTING
        job = scope.launch { runLoop(config) }
    }

    @Synchronized
    fun stop() = start(null)

    private enum class Outcome { DROPPED, AUTH_FAILED }
    private sealed interface Incoming { data class Text(val text: String) : Incoming; data object Closed : Incoming }

    private suspend fun runLoop(cfg: HaConfig) {
        val backoff = Backoff()
        while (scope.isActive) {
            _status.value = HaStatus.CONNECTING
            val outcome = connectOnce(cfg, backoff)
            if (_disconnectedSince.value == null) _disconnectedSince.value = now()
            if (outcome == Outcome.AUTH_FAILED) {
                _status.value = HaStatus.AUTH_FAILED
                sleep(AUTH_RETRY_MS)
            } else {
                _status.value = HaStatus.DISCONNECTED
                sleep(backoff.nextDelayMs())
            }
        }
    }

    private suspend fun connectOnce(cfg: HaConfig, backoff: Backoff): Outcome {
        val inbox = Channel<Incoming>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.trySend(Incoming.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.trySend(Incoming.Closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.trySend(Incoming.Closed) }
        }
        // A malformed URL makes OkHttp throw; treat it like "can't connect" instead of crashing the launcher.
        val request = try {
            Request.Builder().url(toWebSocketUrl(cfg.url)).build()
        } catch (_: IllegalArgumentException) {
            return Outcome.DROPPED
        }
        val ws = http.newWebSocket(request, listener)
        nextId.set(1)
        var weatherSubId = -1
        var firstWeatherEvent = true
        try {
            for (incoming in inbox) {
                if (incoming is Incoming.Closed) return Outcome.DROPPED
                when (val msg = parseInbound((incoming as Incoming.Text).text)) {
                    HaInbound.AuthRequired -> ws.send(buildJsonObject {
                        put("type", "auth"); put("access_token", cfg.token)
                    }.toString())
                    HaInbound.AuthOk -> {
                        _disconnectedSince.value = null
                        backoff.reset()
                        weatherSubId = nextId.getAndIncrement()
                        val ids = listOfNotNull(cfg.weatherEntity, tvEntityId, spotifyEntityId).distinct()
                        ws.send(buildJsonObject {
                            put("id", weatherSubId); put("type", "subscribe_entities")
                            putJsonArray("entity_ids") { ids.forEach { add(it) } }
                        }.toString())
                        // Publish CONNECTED only once the subscribe is sent and the socket is usable, so a command
                        // issued the moment status flips can neither be refused nor jump ahead of the subscribe.
                        liveSocket = ws
                        _status.value = HaStatus.CONNECTED
                        // Last, so a collector reacting to the count can already issue requests.
                        _connectionCount.update { it + 1 }
                    }
                    is HaInbound.AuthInvalid -> return Outcome.AUTH_FAILED
                    is HaInbound.Event -> if (msg.id != weatherSubId) {
                        subscriptions[msg.id]?.trySend(msg.event)
                    } else {
                        if (firstWeatherEvent) {
                            // HA's first event lists every subscribed entity that exists.
                            firstWeatherEvent = false
                            val added = msg.event["a"] as? JsonObject
                            _weatherProblem.value =
                                if (added != null && cfg.weatherEntity !in added) "Weather entity not found: ${cfg.weatherEntity}" else null
                        }
                        _weather.value = applyEntityEvent(_weather.value, msg.event, cfg.weatherEntity)
                        tvEntityId?.let { _tvState.value = applyTvEvent(_tvState.value, msg.event, it) }
                        spotifyEntityId?.let { _spotifyState.value = applyStateEvent(_spotifyState.value, msg.event, it) }
                        _weatherUpdatedAt.value = now()
                    }
                    is HaInbound.Result -> if (msg.id == weatherSubId) {
                        if (!msg.success) _weatherProblem.value = "Weather subscription failed"
                    } else {
                        pending.remove(msg.id)?.complete(
                            if (msg.success) HaResult.Ok(msg.result)
                            else HaResult.Error(msg.error ?: HaError("unknown", "")),
                        )
                    }
                    null -> Unit
                }
            }
            return Outcome.DROPPED
        } finally {
            liveSocket = null
            pending.values.forEach { it.complete(HaResult.NotConnected) }
            pending.clear()
            subscriptions.values.forEach { it.close() }
            subscriptions.clear()
            ws.close(1000, null)
        }
    }

    companion object {
        const val AUTH_RETRY_MS = 5 * 60 * 1000L

        /** Pings every 30s so a dead connection (HA box lost power, router rebooted, TV woke up) is noticed. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    }
}

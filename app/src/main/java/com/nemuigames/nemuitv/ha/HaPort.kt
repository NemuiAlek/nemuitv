package com.nemuigames.nemuitv.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L

data class HaError(val code: String, val message: String)

sealed interface HaResult {
    data class Ok(val result: JsonElement?) : HaResult
    data class Error(val error: HaError) : HaResult
    data object NotConnected : HaResult
    data object Timeout : HaResult
}

/** A live HA subscription; [events] completes when the socket drops or [close] is called. */
interface HaSubscription {
    val events: Flow<JsonObject>
    fun close()
}

/** Request/response and subscription access to Home Assistant over the one shared socket. */
interface HaPort {
    val status: StateFlow<HaStatus>

    /** +1 on every auth_ok (a reload trigger for anything that caches registry data). */
    val connectionCount: StateFlow<Int>

    suspend fun request(type: String, payload: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): HaResult

    /** Null = not connected or refused by HA. */
    suspend fun subscribe(type: String, payload: JsonObject = JsonObject(emptyMap())): HaSubscription?

    /**
     * HA answers `call_service` only once the service has finished, which for a car includes waking it (Tesla
     * retries for up to ~30 s), so slow callers pass a longer [timeoutMs].
     */
    suspend fun callServiceForResult(
        domain: String, service: String, data: JsonObject, target: JsonObject? = null, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): HaResult
}

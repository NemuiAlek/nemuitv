package com.nemuigames.nemuitv.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

    suspend fun request(type: String, payload: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 10_000): HaResult

    /** Null = not connected or refused by HA. */
    suspend fun subscribe(type: String, payload: JsonObject = JsonObject(emptyMap())): HaSubscription?

    suspend fun callServiceForResult(domain: String, service: String, data: JsonObject, target: JsonObject? = null): HaResult
}

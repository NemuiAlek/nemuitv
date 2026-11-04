package com.nemuigames.nemuitv.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WeatherSnapshot(val condition: String, val temperature: Double, val unit: String)

/** The Home Assistant WebSocket messages NemuiTV cares about. */
sealed interface HaInbound {
    data object AuthRequired : HaInbound
    data object AuthOk : HaInbound
    data class AuthInvalid(val message: String) : HaInbound
    data class Result(
        val id: Int,
        val success: Boolean,
        val result: JsonElement? = null,
        val error: HaError? = null,
    ) : HaInbound
    data class Event(val id: Int, val event: JsonObject) : HaInbound
}

/** Parses one incoming message; anything we don't use (or can't read) → null. */
fun parseInbound(text: String): HaInbound? {
    val msg = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
    val id = (msg["id"] as? JsonPrimitive)?.intOrNull
    return when ((msg["type"] as? JsonPrimitive)?.contentOrNull) {
        "auth_required" -> HaInbound.AuthRequired
        "auth_ok" -> HaInbound.AuthOk
        "auth_invalid" -> HaInbound.AuthInvalid((msg["message"] as? JsonPrimitive)?.contentOrNull ?: "")
        "result" -> id?.let {
            val err = msg["error"] as? JsonObject
            HaInbound.Result(
                it, (msg["success"] as? JsonPrimitive)?.booleanOrNull == true, msg["result"]?.takeIf { r -> r !is JsonNull },
                err?.let { e -> HaError(e.str("code") ?: "unknown", e.str("message") ?: "") },
            )
        }
        "event" -> id?.let { i -> (msg["event"] as? JsonObject)?.let { HaInbound.Event(i, it) } }
        else -> null
    }
}

private val NO_WEATHER_STATES = setOf("unavailable", "unknown")

/**
 * Applies one `subscribe_entities` event (HA's compressed format: "a" = added, "c" = changed, "r" = removed)
 * to the current weather for [entityId]. Returns null when the weather can't be shown.
 */
fun applyEntityEvent(current: WeatherSnapshot?, event: JsonObject, entityId: String): WeatherSnapshot? {
    (event["r"] as? JsonArray)?.let { removed ->
        if (removed.any { (it as? JsonPrimitive)?.contentOrNull == entityId }) return null
    }
    (event["a"] as? JsonObject)?.get(entityId)?.let { added ->
        val o = added.jsonObject
        val attrs = o["a"] as? JsonObject
        return snapshot(o.str("s"), attrs?.num("temperature"), attrs?.str("temperature_unit"))
    }
    ((event["c"] as? JsonObject)?.get(entityId) as? JsonObject)?.let { changed ->
        val plus = changed["+"] as? JsonObject ?: return current
        val attrs = plus["a"] as? JsonObject
        return snapshot(
            plus.str("s") ?: current?.condition,
            attrs?.num("temperature") ?: current?.temperature,
            attrs?.str("temperature_unit") ?: current?.unit,
        )
    }
    return current
}

private fun snapshot(condition: String?, temperature: Double?, unit: String?): WeatherSnapshot? {
    if (condition == null || condition in NO_WEATHER_STATES || temperature == null) return null
    return WeatherSnapshot(condition, temperature, unit ?: "°")
}

/** Tracks just the state string ("playing", "paused", …) of [entityId] from a `subscribe_entities` event; removed → null. */
fun applyStateEvent(current: String?, event: JsonObject, entityId: String): String? {
    (event["r"] as? JsonArray)?.let { removed ->
        if (removed.any { (it as? JsonPrimitive)?.contentOrNull == entityId }) return null
    }
    (event["a"] as? JsonObject)?.get(entityId)?.let { return (it as? JsonObject)?.str("s") }
    ((event["c"] as? JsonObject)?.get(entityId) as? JsonObject)?.let { changed ->
        return (changed["+"] as? JsonObject)?.str("s") ?: current
    }
    return current
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

const val STALE_AFTER_MS = 30 * 60 * 1000L

/**
 * Weather counts as stale (drawn dimmed) once we've been cut off from HA for more than 30 minutes.
 * While connected it's live, even if HA hasn't sent a change for an hour (met.no only updates hourly).
 */
fun isStale(disconnectedSince: Long?, now: Long): Boolean =
    disconnectedSince != null && now - disconnectedSince > STALE_AFTER_MS

/** What we know about the TV's media_player entity. [source] is null until HA reports it. */
data class TvState(val available: Boolean, val source: String?)

/** Applies one `subscribe_entities` event to the TV state for [entityId] (same compressed format as [applyEntityEvent]). */
fun applyTvEvent(current: TvState?, event: JsonObject, entityId: String): TvState? {
    (event["r"] as? JsonArray)?.let { removed ->
        if (removed.any { (it as? JsonPrimitive)?.contentOrNull == entityId }) return null
    }
    (event["a"] as? JsonObject)?.get(entityId)?.let { added ->
        val o = added.jsonObject
        val attrs = o["a"] as? JsonObject
        return TvState(
            available = o.str("s") !in NO_WEATHER_STATES,
            source = attrs?.str("source"),
        )
    }
    ((event["c"] as? JsonObject)?.get(entityId) as? JsonObject)?.let { changed ->
        val plus = changed["+"] as? JsonObject ?: return current
        val attrs = plus["a"] as? JsonObject
        val base = current ?: TvState(true, null)
        return TvState(
            available = plus.str("s")?.let { it !in NO_WEATHER_STATES } ?: base.available,
            source = attrs?.str("source") ?: base.source,
        )
    }
    return current
}

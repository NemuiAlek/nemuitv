package com.nemuigames.nemuitv.alerts

import com.nemuigames.nemuitv.hub.EntityState
import com.nemuigames.nemuitv.hub.Registries
import com.nemuigames.nemuitv.hub.areaIdOf
import com.nemuigames.nemuitv.hub.domainOf
import com.nemuigames.nemuitv.hub.isHouseEntity
import com.nemuigames.nemuitv.hub.isUnavailable
import com.nemuigames.nemuitv.hub.isUsableEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

const val LABEL_ALERT = "nemuitv_alert"
private const val MAX_AGE_MS = 60_000L

enum class AlertKind { DOORBELL, BANNER }

data class AlertSource(
    val entityId: String, val kind: AlertKind, val cameraId: String?, val deviceName: String?, val areaName: String?,
)

/** Labelled, usable `event.*` entities. Registries carry no event_types, so every kind starts as BANNER; see [classify]. */
fun alertSources(reg: Registries, excludedAreas: List<String> = emptyList()): List<AlertSource> {
    val labelId = reg.labels.firstOrNull { it.name == LABEL_ALERT }?.id ?: return emptyList()
    val areaById = reg.areas.associateBy { it.id }
    val deviceById = reg.devices.associateBy { it.id }
    return reg.entities.filter { domainOf(it.entityId) == "event" && isUsableEntity(it) && labelId in it.labels }
        .map { e ->
            val camera = e.deviceId?.let { dev ->
                reg.entities.firstOrNull {
                    it.deviceId == dev && domainOf(it.entityId) == "camera" && isUsableEntity(it) &&
                        isHouseEntity(it, deviceById, areaById, excludedAreas)
                }?.entityId
            }
            val device = e.deviceId?.let { deviceById[it] }
            AlertSource(
                e.entityId, AlertKind.BANNER, camera,
                device?.let { it.nameByUser?.takeIf(String::isNotBlank) ?: it.name?.takeIf(String::isNotBlank) },
                areaIdOf(e, deviceById)?.let { areaById[it]?.name },
            )
        }
}

/** DOORBELL iff the live state's `event_types` contains "ring". */
fun classify(source: AlertSource, state: EntityState?): AlertKind {
    val types = state?.attributes?.get("event_types") as? JsonArray
    val ring = types?.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "ring" } == true
    return if (ring) AlertKind.DOORBELL else AlertKind.BANNER
}

data class BannerText(val icon: String, val text: String, val sub: String?)

private val BUILT_IN = mapOf(
    "washing_is_complete" to ("🧺" to "Washer done"),
    "drying_is_complete" to ("🧺" to "Dryer done"),
    "error_during_washing" to ("⚠" to "Washer problem"),
    "drying_failed" to ("⚠" to "Dryer problem"),
)

fun bannerText(eventType: String?, source: AlertSource, overrides: Map<String, String>): BannerText {
    val sub = source.areaName
    val builtIn = BUILT_IN[eventType]
    val override = overrides[eventType]?.takeIf(String::isNotBlank)
    if (builtIn != null) return BannerText(builtIn.first, override ?: builtIn.second, sub)
    if (override != null) return BannerText("🔔", override, sub)
    val pretty = eventType?.replace('_', ' ')?.trim()?.replaceFirstChar { it.uppercase() }
    val text = listOfNotNull(source.deviceName, pretty).joinToString(" · ").ifEmpty { source.entityId }
    return BannerText("🔔", text, sub)
}

/**
 * No-replay guard. The first parseable state per entity after [reset] is remembered, never fired.
 * Later, a strictly newer timestamp that is at most 60 s old by the wall clock fires its `event_type`.
 */
class AlertTracker(private val wallClock: () -> Long) {
    private val last = HashMap<String, Long>()

    fun reset() = last.clear()

    fun onState(entityId: String, state: EntityState?): String? {
        if (isUnavailable(state)) return null
        val ts = try {
            OffsetDateTime.parse(state!!.state).toInstant().toEpochMilli()
        } catch (_: Exception) {
            return null
        }
        val prev = last[entityId]
        if (prev == null) {
            last[entityId] = ts
            return null
        }
        if (ts <= prev) return null
        last[entityId] = ts
        if (wallClock() - ts > MAX_AGE_MS) return null
        return (state.attributes["event_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * A new doorbell ring (none showing before, or a different ring): every Control Center closes so the pop-up is seen
 * (alerts step aside while the Control Center is open, except a ring). Expanding the same ring doesn't.
 */
fun ringClosesCc(before: DoorbellUi?, now: DoorbellUi?): Boolean =
    now != null && (before == null || before.ringAtMs != now.ringAtMs)

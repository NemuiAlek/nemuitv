package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonObject

data class AreaEntry(val id: String, val name: String)
data class DeviceEntry(val id: String, val areaId: String?, val name: String?, val manufacturer: String?, val nameByUser: String? = null)
data class EntityEntry(
    val entityId: String, val deviceId: String?, val areaId: String?, val name: String?, val originalName: String?,
    val labels: Set<String>, val entityCategory: String?, val hiddenBy: String?, val disabledBy: String?,
    val hasEntityName: Boolean = false,
)
data class LabelEntry(val id: String, val name: String)
data class Registries(val areas: List<AreaEntry>, val devices: List<DeviceEntry>, val entities: List<EntityEntry>, val labels: List<LabelEntry>)
/** [lastChangedMs] is HA's `last_changed` (epoch ms) when the event carried it, else null. */
data class EntityState(val state: String, val attributes: JsonObject, val lastChangedMs: Long? = null)
data class Tile(val entityId: String, val name: String, val domain: String, val keepOn: Boolean)
data class Room(val areaId: String, val name: String, val tiles: List<Tile>)
data class HubModel(
    val rooms: List<Room>, val scenes: List<String>, val cameras: List<String>, val thermostat: String?,
    val locks: List<String>, val lockBatteries: List<String>, val people: List<String>, val cars: List<String>,
    /** script id -> input_boolean id, for scenes whose on/off state is mirrored by a flag (e.g. Guest mode). */
    val sceneFlags: Map<String, String> = emptyMap(),
    /** Room tiles whose device reports a running cycle (washer, dryer…); see [Cycle]. */
    val cycles: List<Cycle> = emptyList(),
    /** Car prefix -> `climate.<p>_climate`, only for cars whose climate entity exists and is enabled. */
    val carClimate: Map<String, String> = emptyMap(),
    /** Car prefix -> `climate.<p>_cabin_overheat_protection`, same rule. */
    val carCop: Map<String, String> = emptyMap(),
)

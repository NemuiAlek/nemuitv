package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig

private const val LABEL_SCENE = "nemuitv"
private const val LABEL_KEEP_ON = "keep_on"
private val TILE_DOMAINS = setOf("light", "switch", "fan")

internal fun domainOf(entityId: String) = entityId.substringBefore('.')

/** The entity's own area, else its device's area. */
internal fun areaIdOf(e: EntityEntry, deviceById: Map<String, DeviceEntry>): String? =
    e.areaId ?: e.deviceId?.let { deviceById[it]?.areaId }

/** House = has an area and that area's name is not in [excludedAreas] (`hub.excludedAreas`). */
internal fun isHouseEntity(
    e: EntityEntry, deviceById: Map<String, DeviceEntry>, areaById: Map<String, AreaEntry>, excludedAreas: List<String>,
): Boolean {
    val a = areaIdOf(e, deviceById) ?: return false
    return areaById[a]?.name !in excludedAreas
}

internal fun isUsableEntity(e: EntityEntry) = e.hiddenBy == null && e.disabledBy == null

fun isOn(state: EntityState?): Boolean = state?.state == "on"

fun isUnavailable(state: EntityState?): Boolean =
    state == null || state.state == "unavailable" || state.state == "unknown"

fun roomOffTargets(room: Room, states: Map<String, EntityState>): List<String> =
    room.tiles.filter { !it.keepOn && !isUnavailable(states[it.entityId]) }.map { it.entityId }

/** Room off would change something: at least one of its targets is on right now. */
fun roomOffActive(room: Room, states: Map<String, EntityState>): Boolean =
    roomOffTargets(room, states).any { isOn(states[it]) }

fun buildHubModel(reg: Registries, cfg: HubConfig): HubModel {
    val areaById = reg.areas.associateBy { it.id }
    val deviceById = reg.devices.associateBy { it.id }
    val sceneLabel = reg.labels.firstOrNull { it.name == LABEL_SCENE }?.id
    val keepOnLabel = reg.labels.firstOrNull { it.name == LABEL_KEEP_ON }?.id

    fun areaOf(e: EntityEntry): String? = areaIdOf(e, deviceById)
    fun isHouse(e: EntityEntry): Boolean = isHouseEntity(e, deviceById, areaById, cfg.excludedAreas)
    fun usable(e: EntityEntry) = isUsableEntity(e)
    // A blank name (HA can store "") counts as missing at every step.
    fun String?.orMissing(): String? = this?.takeIf { it.isNotBlank() }
    fun deviceName(e: EntityEntry): String? =
        e.deviceId?.let { deviceById[it] }?.let { it.nameByUser.orMissing() ?: it.name.orMissing() }
    fun displayName(e: EntityEntry): String {
        e.name.orMissing()?.let { return it }
        val dev = deviceName(e)
        val orig = e.originalName.orMissing()
        if (e.hasEntityName && dev != null) return orig?.let { "$dev $it" } ?: dev
        return orig ?: dev ?: e.entityId
    }

    val teslaDevices = reg.devices.filter { it.manufacturer == "Tesla" }.map { it.id }
    val teslaDeviceIds = teslaDevices.toSet()
    // A car is never part of the house, even when its device has an HA area: its climate / overheat entity must not
    // become the thermostat and its switches must not become room tiles (the Tesla pane shows the car).
    val houseEntities = reg.entities.filter { usable(it) && isHouse(it) && it.deviceId !in teslaDeviceIds }

    val tilesByArea = LinkedHashMap<String, MutableList<Tile>>()
    houseEntities.filter { domainOf(it.entityId) in TILE_DOMAINS && it.entityCategory == null }.forEach { e ->
        tilesByArea.getOrPut(areaOf(e)!!) { mutableListOf() }
            .add(Tile(e.entityId, displayName(e), domainOf(e.entityId), keepOnLabel != null && keepOnLabel in e.labels))
    }
    val rooms = tilesByArea.map { (areaId, tiles) ->
        Room(areaId, areaById[areaId]?.name ?: areaId, tiles.sortedBy { it.name.lowercase() })
    }.sortedBy { it.name.lowercase() }

    val sceneEntities = if (sceneLabel == null) emptyList() else
        reg.entities.filter { usable(it) && domainOf(it.entityId) == "script" && sceneLabel in it.labels }
    val scenes = sceneEntities.sortedWith(
        compareBy<EntityEntry>(
            { cfg.sceneOrder.indexOf(it.entityId).let { i -> if (i < 0) cfg.sceneOrder.size else i } },
            { displayName(it).lowercase() },
        )
    ).map { it.entityId }

    val cameras = houseEntities.filter { domainOf(it.entityId) == "camera" }
        .sortedWith(compareBy<EntityEntry>({ if ("doorbell" in it.entityId) 0 else 1 }, { it.entityId }))
        .map { it.entityId }

    val thermostat = houseEntities.filter { domainOf(it.entityId) == "climate" }.minByOrNull { displayName(it).lowercase() }?.entityId

    val houseLocks = houseEntities.filter { domainOf(it.entityId) == "lock" }
    val lockDevices = houseLocks.mapNotNull { it.deviceId }.toSet()
    val lockBatteries = reg.entities.filter {
        it.deviceId in lockDevices && domainOf(it.entityId) == "sensor"
    }.map { it.entityId }

    val people = reg.entities.filter { domainOf(it.entityId) == "person" && usable(it) && it.entityId !in cfg.hiddenPeople }
        .map { it.entityId }

    val cars = teslaDevices.mapNotNull { devId ->
        reg.entities.firstOrNull {
            // Disabled = the integration/entity was turned off in HA, so no car. Hidden still counts: users hide car sensors.
            it.deviceId == devId && it.disabledBy == null &&
                it.entityId.startsWith("sensor.") && it.entityId.endsWith("_battery_level")
        }?.entityId?.removePrefix("sensor.")?.removeSuffix("_battery_level")
    }

    // A scene script.<x> is flagged by input_boolean.<x> when that entity exists and is usable (area not needed).
    val usableBooleans = reg.entities.filter { usable(it) && domainOf(it.entityId) == "input_boolean" }
        .map { it.entityId }.toSet()
    val sceneFlags = scenes.mapNotNull { id ->
        "input_boolean.${id.substringAfter('.')}".takeIf { it in usableBooleans }?.let { id to it }
    }.toMap()

    // A tile's device with a `*_remaining_time` sensor runs cycles (washer, dryer…): its bar + time left.
    val tileIds = rooms.flatMap { r -> r.tiles.map { it.entityId } }.toSet()
    val sensorsByDevice = reg.entities.filter { usable(it) && it.deviceId != null && domainOf(it.entityId) == "sensor" }
        .groupBy { it.deviceId!! }
    val cycles = houseEntities.filter { it.entityId in tileIds && it.deviceId != null }.mapNotNull { t ->
        val sensors = sensorsByDevice[t.deviceId].orEmpty().map { it.entityId }
        fun bySuffix(suffix: String) = sensors.firstOrNull { it.endsWith(suffix) }
        val remaining = bySuffix("_remaining_time") ?: return@mapNotNull null
        Cycle(t.entityId, deviceName(t) ?: displayName(t), remaining, bySuffix("_total_time"), bySuffix("_current_status"))
    }

    return HubModel(
        rooms, scenes, cameras, thermostat, houseLocks.map { it.entityId }, lockBatteries, people, cars, sceneFlags, cycles,
    )
}

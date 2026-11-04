package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.objects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun parseRegistries(areas: JsonElement?, devices: JsonElement?, entities: JsonElement?, labels: JsonElement?): Registries =
    Registries(
        areas = areas.objects().mapNotNull { o ->
            val id = o.str("area_id") ?: return@mapNotNull null
            AreaEntry(id, o.str("name") ?: id)
        },
        devices = devices.objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            DeviceEntry(id, o.str("area_id"), o.str("name"), o.str("manufacturer"), o.str("name_by_user"))
        },
        entities = entities.objects().mapNotNull { o ->
            val id = o.str("entity_id") ?: return@mapNotNull null
            EntityEntry(
                entityId = id,
                deviceId = o.str("device_id"),
                areaId = o.str("area_id"),
                name = o.str("name"),
                originalName = o.str("original_name"),
                labels = (o["labels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),
                entityCategory = o.str("entity_category"),
                hiddenBy = o.str("hidden_by"),
                disabledBy = o.str("disabled_by"),
                hasEntityName = (o["has_entity_name"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        },
        labels = labels.objects().mapNotNull { o ->
            val id = o.str("label_id") ?: return@mapNotNull null
            LabelEntry(id, o.str("name") ?: id)
        },
    )

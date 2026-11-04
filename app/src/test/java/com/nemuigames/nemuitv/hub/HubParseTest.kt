package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubParseTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun parse_realShapes() {
        val r = parseRegistries(
            j("""[{"area_id":"front_yard","name":"Front Yard","floor_id":null,"icon":null},{"area_id":"guest_house","name":"Guest House"}]"""),
            j("""[{"area_id":"front_yard","id":"a811","manufacturer":"August Home Inc.","name_by_user":"Front Door","name":"Front Door"},
                  {"area_id":null,"id":"abe2","manufacturer":"Tesla","name_by_user":null,"name":"My Car"}]"""),
            j("""[{"entity_id":"sensor.front_door_battery","device_id":"a811","area_id":null,"name":null,"original_name":"Battery","has_entity_name":true,"labels":[],"entity_category":"diagnostic","hidden_by":null,"disabled_by":null},
                  {"entity_id":"script.night_mode","device_id":null,"area_id":null,"name":null,"original_name":"Night mode","has_entity_name":false,"labels":["nemuitv"],"entity_category":null,"hidden_by":null,"disabled_by":null}]"""),
            j("""[{"label_id":"keep_on","name":"keep_on","color":"amber"},{"label_id":"nemuitv","name":"nemuitv"}]"""),
        )
        assertEquals(listOf(AreaEntry("front_yard", "Front Yard"), AreaEntry("guest_house", "Guest House")), r.areas)
        assertEquals(DeviceEntry("a811", "front_yard", "Front Door", "August Home Inc.", "Front Door"), r.devices[0])
        assertEquals(null, r.devices[1].areaId)
        assertEquals(
            EntityEntry("sensor.front_door_battery", "a811", null, null, "Battery", emptySet(), "diagnostic", null, null, true),
            r.entities[0],
        )
        assertEquals(setOf("nemuitv"), r.entities[1].labels)
        assertEquals(false, r.entities[1].hasEntityName)
        assertEquals(listOf(LabelEntry("keep_on", "keep_on"), LabelEntry("nemuitv", "nemuitv")), r.labels)
    }

    @Test fun parse_missingFieldsTolerated() {
        val r = parseRegistries(null, j("""[{"foo":1},{"id":"x"}]"""), j("""[{"entity_id":"light.a"},"junk",{"name":"no id"}]"""), j("{}"))
        assertTrue(r.areas.isEmpty())
        assertEquals(listOf(DeviceEntry("x", null, null, null)), r.devices)
        assertEquals(EntityEntry("light.a", null, null, null, null, emptySet(), null, null, null, false), r.entities.single())
        assertTrue(r.labels.isEmpty())
    }
}

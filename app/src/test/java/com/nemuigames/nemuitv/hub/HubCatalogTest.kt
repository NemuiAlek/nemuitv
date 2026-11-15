package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubCatalogTest {
    private val areas = listOf(
        AreaEntry("living_room", "Living Room"), AreaEntry("kitchen", "Kitchen"),
        AreaEntry("front_yard", "Front Yard"), AreaEntry("guest_house", "Guest House"),
        AreaEntry("empty", "Empty"),
    )
    private val devices = listOf(
        DeviceEntry("d_lock", "front_yard", "Front Door", "August", nameByUser = "Front Door"),
        DeviceEntry("d_back", "guest_house", "Back Door", "August"),
        DeviceEntry("d_laundry", "living_room", "Laundry Light", "TP-Link"),
        DeviceEntry("d_plug", "kitchen", "Kitchen Plug", "TP-Link", nameByUser = "Kitchen Plug User"),
        DeviceEntry("d_car1", null, "My Car", "Tesla"),
        DeviceEntry("d_car2", null, "Second Car", "Tesla"),
    )

    private fun e(
        id: String, device: String? = null, area: String? = null, name: String? = null, orig: String? = null,
        labels: Set<String> = emptySet(), cat: String? = null, hidden: String? = null, disabled: String? = null,
        hasName: Boolean = false,
    ) = EntityEntry(id, device, area, name, orig, labels, cat, hidden, disabled, hasName)

    private val entities = listOf(
        e("light.zlamp", area = "living_room", name = "Z Lamp"),
        e("light.alamp", area = "living_room", name = "A Lamp", labels = setOf("keep_on")),
        e("light.laundry_light", device = "d_laundry", hasName = true),
        e("switch.kitchen", device = "d_plug", area = "kitchen", name = "Plug"),
        e("switch.x_led", area = "kitchen", name = "LED", cat = "config"),
        e("sensor.temp", area = "living_room"),
        e("light.hidden", area = "living_room", hidden = "user"),
        e("light.disabled", area = "living_room", disabled = "user"),
        e("light.noarea"),
        e("light.guest", area = "guest_house"),
        e("lock.front_door", device = "d_lock", hasName = true),
        e("sensor.front_door_battery", device = "d_lock", cat = "diagnostic"),
        e("lock.back_door", device = "d_back"),
        e("lock.second_car_lock"),
        e("camera.front_yard_camera", area = "front_yard"),
        e("camera.backyard_camera", area = "front_yard"),
        e("camera.front_yard_doorbell", area = "front_yard"),
        e("camera.guest", area = "guest_house"),
        e("climate.second_car_climate"),
        e("climate.house", area = "living_room"),
        e("script.movie_mode", labels = setOf("nemuitv")),
        e("script.zeta", labels = setOf("nemuitv"), orig = "Zeta"),
        e("script.alpha", labels = setOf("nemuitv"), orig = "Alpha"),
        e("script.night_mode", labels = setOf("nemuitv")),
        e("script.whole_house_off", labels = setOf("nemuitv")),
        e("script.guest_mode", labels = setOf("nemuitv")),
        e("script.unlabeled"),
        e("input_boolean.guest_mode"),
        e("input_boolean.movie_mode", hidden = "user"),
        e("light.labelled", area = "living_room", name = "Labelled", labels = setOf("nemuitv")),
        e("person.sam"), e("person.nemuitv"), e("person.alex"),
        e("sensor.my_car_battery_level", device = "d_car1"),
        e("sensor.second_car_battery_level", device = "d_car2"),
    )
    private val reg = Registries(areas, devices, entities, listOf(LabelEntry("keep_on", "keep_on"), LabelEntry("nemuitv", "nemuitv")))
    private val hubCfg = HubConfig(
        excludedAreas = listOf("Guest House"), hiddenPeople = listOf("person.nemuitv"),
        sceneOrder = listOf("script.whole_house_off", "script.night_mode", "script.guest_mode", "script.movie_mode"),
    )
    private val model = buildHubModel(reg, hubCfg)

    private fun tile(id: String) = model.rooms.flatMap { it.tiles }.first { it.entityId == id }

    @Test fun house_excludesExcludedAreaAndNoArea() {
        val ids = model.rooms.flatMap { it.tiles }.map { it.entityId }
        assertFalse("light.guest" in ids)
        assertFalse("light.noarea" in ids)
        assertTrue("light.alamp" in ids)
        assertFalse(model.rooms.any { it.areaId == "guest_house" })
    }

    @Test fun entityAreaBeatsDeviceArea() {
        // switch.kitchen: device in kitchen, entity also kitchen; light.laundry via device only
        val withOverride = reg.copy(entities = entities + e("light.override", device = "d_laundry", area = "kitchen", name = "O"))
        val m = buildHubModel(withOverride, hubCfg)
        assertTrue(m.rooms.first { it.areaId == "kitchen" }.tiles.any { it.entityId == "light.override" })
        assertTrue(m.rooms.first { it.areaId == "living_room" }.tiles.any { it.entityId == "light.laundry_light" })
        val guest = buildHubModel(reg.copy(entities = entities + e("light.m2", device = "d_laundry", area = "guest_house", name = "M2")), hubCfg)
        assertFalse(guest.rooms.flatMap { it.tiles }.any { it.entityId == "light.m2" })
    }

    @Test fun tiles_onlyLightSwitchFan() {
        val ids = model.rooms.flatMap { it.tiles }.map { it.entityId }
        assertFalse("sensor.temp" in ids)
        assertEquals(setOf("light", "switch"), model.rooms.flatMap { it.tiles }.map { it.domain }.toSet())
    }

    @Test fun `tiles skipHiddenDisabledAndEntityCategory`() {
        val ids = model.rooms.flatMap { it.tiles }.map { it.entityId }
        assertFalse("switch.x_led" in ids)
        assertFalse("light.hidden" in ids)
        assertFalse("light.disabled" in ids)
    }

    @Test fun `tileName entityNameElseOriginalElseDevice`() {
        assertEquals("A Lamp", tile("light.alamp").name)
        assertEquals("Laundry Light", tile("light.laundry_light").name)
        val extra = reg.copy(entities = entities + listOf(
            e("light.a", device = "d_plug", area = "kitchen", hasName = true, orig = "Strip"),
            e("light.b", area = "kitchen", orig = "Orig Only"),
            e("light.c", area = "kitchen"),
        ))
        val tiles = buildHubModel(extra, hubCfg).rooms.flatMap { it.tiles }.associateBy { it.entityId }
        assertEquals("Kitchen Plug User Strip", tiles.getValue("light.a").name)
        assertEquals("Orig Only", tiles.getValue("light.b").name)
        assertEquals("light.c", tiles.getValue("light.c").name)
    }

    @Test fun tileName_blankNamesCountAsMissing() {
        val extra = reg.copy(
            devices = devices + DeviceEntry("d_blank", "kitchen", "Blank Dev", "X", nameByUser = " "),
            entities = entities + listOf(
                e("light.blank1", area = "kitchen", name = "", orig = "Orig Name"),
                e("light.blank2", device = "d_blank", area = "kitchen", name = " ", orig = "", hasName = true),
                e("light.blank3", area = "kitchen", name = "", orig = " "),
            ),
        )
        val tiles = buildHubModel(extra, hubCfg).rooms.flatMap { it.tiles }.associateBy { it.entityId }
        assertEquals("Orig Name", tiles.getValue("light.blank1").name)
        assertEquals("Blank Dev", tiles.getValue("light.blank2").name)
        assertEquals("light.blank3", tiles.getValue("light.blank3").name)
    }

    @Test fun tiles_keepOnFlag() {
        assertTrue(tile("light.alamp").keepOn)
        assertFalse(tile("light.zlamp").keepOn)
    }

    @Test fun rooms_withoutTilesHidden() {
        assertFalse(model.rooms.any { it.areaId == "empty" || it.areaId == "front_yard" })
    }

    @Test fun rooms_sortedByName() {
        assertEquals(listOf("Kitchen", "Living Room"), model.rooms.map { it.name })
        assertEquals(listOf("A Lamp", "Labelled", "Laundry Light", "Z Lamp"), model.rooms[1].tiles.map { it.name })
    }

    @Test fun scenes_nemuitvScriptsInFixedOrder() {
        assertEquals(
            listOf(
                "script.whole_house_off", "script.night_mode", "script.guest_mode", "script.movie_mode",
                "script.alpha", "script.zeta",
            ),
            model.scenes,
        )
        assertFalse("light.labelled" in model.scenes)
    }

    @Test fun `scenes emptySceneOrder allUnlistedAlphabetical`() {
        val m = buildHubModel(reg, HubConfig())
        assertEquals(
            listOf("script.alpha", "script.guest_mode", "script.movie_mode", "script.night_mode", "script.whole_house_off", "script.zeta"),
            m.scenes,
        )
    }

    @Test fun `scenes customSceneOrder listedFirstThenRestAlphabetical`() {
        val m = buildHubModel(reg, HubConfig(sceneOrder = listOf("script.zeta", "script.movie_mode")))
        assertEquals(
            listOf("script.zeta", "script.movie_mode", "script.alpha", "script.guest_mode", "script.night_mode", "script.whole_house_off"),
            m.scenes,
        )
    }

    @Test fun `excludedAreas empty keepsEveryAreaWithAnArea`() {
        val m = buildHubModel(reg, HubConfig())
        assertTrue(m.rooms.any { it.areaId == "guest_house" })
        assertTrue("lock.back_door" in m.locks)
    }

    @Test fun `excludedAreas excludesTilesLocksAndCameras`() {
        assertFalse(model.rooms.any { it.areaId == "guest_house" })
        assertFalse("lock.back_door" in model.locks)
        assertFalse("camera.guest" in model.cameras)
    }

    @Test fun hiddenPeople_empty_showsEveryone() {
        assertEquals(listOf("person.sam", "person.nemuitv", "person.alex"), buildHubModel(reg, HubConfig()).people)
    }

    @Test fun sceneFlags_matchInputBooleanByObjectId() {
        // night_mode has no boolean; the guest boolean has no area, which is fine.
        assertEquals(mapOf("script.guest_mode" to "input_boolean.guest_mode"), model.sceneFlags)
    }

    @Test fun sceneFlags_hiddenBooleanIgnored() {
        // input_boolean.movie_mode exists but is hidden.
        assertFalse("script.movie_mode" in model.sceneFlags)
    }

    @Test fun cameras_doorbellFirstThenByName() {
        assertEquals(
            listOf("camera.front_yard_doorbell", "camera.backyard_camera", "camera.front_yard_camera"),
            model.cameras,
        )
    }

    @Test fun thermostat_houseClimateOnly() {
        assertEquals("climate.house", model.thermostat)
        val two = buildHubModel(reg.copy(entities = entities + e("climate.a", area = "kitchen", name = "AAA")), hubCfg)
        assertEquals("climate.a", two.thermostat)
        val none = buildHubModel(reg.copy(entities = entities.filter { it.entityId != "climate.house" }), hubCfg)
        assertEquals(null, none.thermostat)
    }

    @Test fun locks_houseOnly() {
        assertEquals(listOf("lock.front_door"), model.locks)
        assertEquals(listOf("sensor.front_door_battery"), model.lockBatteries)
    }

    @Test fun people_excludeTvUser() {
        assertEquals(listOf("person.sam", "person.alex"), model.people)
    }

    @Test fun cars_fromTeslaDevices() {
        assertEquals(listOf("my_car", "second_car"), model.cars)
    }

    @Test fun cars_disabledBatterySensorIsNoCar() {
        val m = buildHubModel(
            reg.copy(entities = entities.map {
                if (it.entityId == "sensor.my_car_battery_level") it.copy(disabledBy = "config_entry") else it
            }),
            hubCfg,
        )
        assertEquals(listOf("second_car"), m.cars)
    }

    @Test fun cars_hiddenBatterySensorStillCounts() {
        val m = buildHubModel(
            reg.copy(entities = entities.map {
                if (it.entityId == "sensor.my_car_battery_level") it.copy(hiddenBy = "user") else it
            }),
            hubCfg,
        )
        assertEquals(listOf("my_car", "second_car"), m.cars)
    }

    private fun st(s: String) = EntityState(s, JsonObject(emptyMap()))
    private val room = Room("r", "R", listOf(
        Tile("light.a", "A", "light", false), Tile("light.k", "K", "light", true),
        Tile("light.u", "U", "light", false), Tile("light.m", "M", "light", false),
    ))

    @Test fun roomOffTargets_skipKeepOnAndUnavailable() {
        val states = mapOf("light.a" to st("on"), "light.k" to st("on"), "light.u" to st("unavailable"))
        assertEquals(listOf("light.a"), roomOffTargets(room.copy(tiles = room.tiles.take(3)), states))
    }

    @Test fun `roomOffTargets emptyWhenAllKeepOnOrUnavailable`() {
        val r = room.copy(tiles = room.tiles.filter { it.entityId in setOf("light.k", "light.u", "light.m") })
        assertEquals(emptyList<String>(), roomOffTargets(r, mapOf("light.k" to st("on"), "light.u" to st("unknown"))))
    }

    @Test fun roomOffActive_onlyWhenATargetIsOn() {
        val r = room.copy(tiles = room.tiles.take(3)) // a, k (keep_on), u
        // Only the keep_on light is on: Room off would change nothing.
        assertFalse(roomOffActive(r, mapOf("light.a" to st("off"), "light.k" to st("on"), "light.u" to st("unavailable"))))
        assertFalse(roomOffActive(r, emptyMap()))
        assertTrue(roomOffActive(r, mapOf("light.a" to st("on"), "light.k" to st("off"))))
    }

    @Test fun isOnAndUnavailable() {
        assertTrue(isOn(st("on")))
        assertFalse(isOn(null))
        assertTrue(isUnavailable(null))
        assertTrue(isUnavailable(st("unavailable")))
        assertTrue(isUnavailable(st("unknown")))
        assertFalse(isUnavailable(st("off")))
    }
}

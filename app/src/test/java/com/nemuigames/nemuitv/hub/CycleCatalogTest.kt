package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.config.HubConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleCatalogTest {
    private fun e(id: String, device: String?, area: String? = null, disabled: String? = null) =
        EntityEntry(id, device, area, null, null, emptySet(), null, null, disabled, hasEntityName = true)

    private val reg = Registries(
        areas = listOf(AreaEntry("laundry_room", "Laundry Room")),
        devices = listOf(
            DeviceEntry("d_w", "laundry_room", "Washer", "LGE"),
            DeviceEntry("d_d", "laundry_room", "Dryer", "LGE", nameByUser = "Big Dryer"),
            DeviceEntry("d_p", "laundry_room", "Plug", "TP-Link"),
        ),
        entities = listOf(
            e("switch.washer_power", "d_w"),
            e("sensor.washer_remaining_time", "d_w"),
            e("sensor.washer_total_time", "d_w"),
            e("sensor.washer_current_status", "d_w"),
            e("switch.dryer_power", "d_d"),
            e("sensor.dryer_remaining_time", "d_d"),
            e("sensor.dryer_total_time", "d_d", disabled = "user"),
            e("switch.plug", "d_p"),
        ),
        labels = emptyList(),
    )
    private val model = buildHubModel(reg, HubConfig())

    @Test fun deviceWithRemainingTime_getsACycle() {
        val w = model.cycles.single { it.tileId == "switch.washer_power" }
        assertEquals(Cycle("switch.washer_power", "Washer", "sensor.washer_remaining_time",
            "sensor.washer_total_time", "sensor.washer_current_status"), w)
        // Device name the user set wins; a disabled total is left out (no bar, minutes only).
        val d = model.cycles.single { it.tileId == "switch.dryer_power" }
        assertEquals("Big Dryer", d.name)
        assertNull(d.total)
        assertNull(d.status)
        assertNull(model.cycles.firstOrNull { it.tileId == "switch.plug" })
    }

    @Test fun cycleSensorsAreSubscribed() {
        val ids = subscribedIds(model)
        for (id in listOf("sensor.washer_remaining_time", "sensor.washer_total_time", "sensor.washer_current_status",
            "sensor.dryer_remaining_time")) assertTrue(id, id in ids)
    }
}

package com.nemuigames.nemuitv.alerts

import com.nemuigames.nemuitv.hub.AreaEntry
import com.nemuigames.nemuitv.hub.DeviceEntry
import com.nemuigames.nemuitv.hub.EntityEntry
import com.nemuigames.nemuitv.hub.EntityState
import com.nemuigames.nemuitv.hub.LabelEntry
import com.nemuigames.nemuitv.hub.Registries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertRulesTest {
    private val areas = listOf(
        AreaEntry("front_yard", "Front Yard"), AreaEntry("laundry", "Laundry Room"),
        AreaEntry("guest_house", "Guest House"),
    )
    private val devices = listOf(
        DeviceEntry("d_bell", "front_yard", "Front Yard Doorbell", "Ring"),
        DeviceEntry("d_washer", "laundry", "Washer", "LG"),
        DeviceEntry("d_dryer", "laundry", "Dryer", "LG"),
        DeviceEntry("d_far", "guest_house", "Far Bell", "Ring"),
    )
    private val labels = listOf(LabelEntry("lbl_alert", "nemuitv_alert"), LabelEntry("lbl_other", "other"))
    private val alert = setOf("lbl_alert")

    private fun e(
        id: String, device: String? = null, area: String? = null, labels: Set<String> = emptySet(),
        hidden: String? = null, disabled: String? = null,
    ) = EntityEntry(id, device, area, null, null, labels, null, hidden, disabled)

    private fun reg(vararg ents: EntityEntry) = Registries(areas, devices, ents.toList(), labels)

    private fun state(state: String, vararg types: String) = EntityState(
        state, JsonObject(mapOf("event_types" to JsonArray(types.map { JsonPrimitive(it) }))),
    )

    @Test fun sources_onlyLabelledEventDomain() {
        val r = reg(
            e("event.front_yard_doorbell_chime", "d_bell", labels = alert),
            e("sensor.x", "d_bell", labels = alert),
            e("event.unlabelled", "d_bell"),
            e("event.other_label", "d_bell", labels = setOf("lbl_other")),
        )
        assertEquals(listOf("event.front_yard_doorbell_chime"), alertSources(r).map { it.entityId })
        assertEquals(AlertKind.BANNER, alertSources(r).single().kind)
    }

    @Test fun sources_hiddenOrDisabledSkipped() {
        val r = reg(
            e("event.a", "d_washer", labels = alert, hidden = "user"),
            e("event.b", "d_washer", labels = alert, disabled = "integration"),
            e("event.c", "d_washer", labels = alert),
        )
        assertEquals(listOf("event.c"), alertSources(r).map { it.entityId })
    }

    @Test fun doorbell_cameraOnSameDevice() {
        val r = reg(
            e("event.front_yard_doorbell_chime", "d_bell", labels = alert),
            e("camera.front_yard_doorbell", "d_bell"),
            e("camera.other", "d_washer"),
        )
        val s = alertSources(r).single()
        assertEquals("camera.front_yard_doorbell", s.cameraId)
        assertEquals("Front Yard Doorbell", s.deviceName)
        assertEquals("Front Yard", s.areaName)
    }

    @Test fun doorbell_cameraInExcludedAreaIgnored() {
        val r = reg(e("event.far", "d_far", labels = alert), e("camera.far", "d_far"))
        assertNull(alertSources(r, listOf("Guest House")).single().cameraId)
    }

    @Test fun `doorbell cameraInAnyArea whenNothingExcluded`() {
        val r = reg(e("event.far", "d_far", labels = alert), e("camera.far", "d_far"))
        assertEquals("camera.far", alertSources(r).single().cameraId)
    }

    @Test fun classify_ringIsDoorbell_elseBanner() {
        val s = AlertSource("event.x", AlertKind.BANNER, null, null, null)
        assertEquals(AlertKind.DOORBELL, classify(s, state("2026-11-03T14:14:35.118+00:00", "ring")))
        assertEquals(AlertKind.BANNER, classify(s, state("x", "washing_is_complete", "error_during_washing")))
        assertEquals(AlertKind.BANNER, classify(s, null))
        assertEquals(AlertKind.BANNER, classify(s, EntityState("x", JsonObject(emptyMap()))))
    }

    @Test fun area_entityThenDevice() {
        val r = reg(
            e("event.washer_notification", "d_washer", labels = alert),
            e("event.dryer_notification", "d_dryer", area = "front_yard", labels = alert),
        )
        val by = alertSources(r).associateBy { it.entityId }
        assertEquals("Laundry Room", by.getValue("event.washer_notification").areaName)
        assertEquals("Front Yard", by.getValue("event.dryer_notification").areaName)
    }

    private val laundry = AlertSource("event.washer_notification", AlertKind.BANNER, null, "Washer", "Laundry Room")

    @Test fun bannerText_lgCodes() {
        assertEquals(BannerText("🧺", "Washer done", "Laundry Room"), bannerText("washing_is_complete", laundry, emptyMap()))
        assertEquals(BannerText("🧺", "Dryer done", "Laundry Room"), bannerText("drying_is_complete", laundry, emptyMap()))
        assertEquals(BannerText("⚠", "Washer problem", "Laundry Room"), bannerText("error_during_washing", laundry, emptyMap()))
        assertEquals(BannerText("⚠", "Dryer problem", "Laundry Room"), bannerText("drying_failed", laundry, emptyMap()))
    }

    @Test fun bannerText_override() {
        val t = bannerText("washing_is_complete", laundry, mapOf("washing_is_complete" to "Laundry's ready"))
        assertEquals("Laundry's ready", t.text)
        assertEquals("Laundry Room", t.sub)
    }

    @Test fun bannerText_fallback() {
        assertEquals(BannerText("🔔", "Washer · Door open error", "Laundry Room"), bannerText("door_open_error", laundry, emptyMap()))
        assertEquals(null, bannerText("x", laundry.copy(areaName = null), emptyMap()).sub)
    }
}

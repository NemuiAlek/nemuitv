package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermostatRulesTest {
    private val allModes = listOf("off", "heat_cool", "cool", "heat")

    private fun climate(
        state: String, temperature: Double? = null, current: Double? = null,
        low: Double? = null, high: Double? = null, min: Double? = null, max: Double? = null,
        modes: List<String>? = allModes,
    ) = EntityState(state, JsonObject(buildMap {
        if (modes != null) put("hvac_modes", JsonArray(modes.map { JsonPrimitive(it) }))
        if (temperature != null) put("temperature", JsonPrimitive(temperature))
        if (current != null) put("current_temperature", JsonPrimitive(current))
        if (low != null) put("target_temp_low", JsonPrimitive(low))
        if (high != null) put("target_temp_high", JsonPrimitive(high))
        if (min != null) put("min_temp", JsonPrimitive(min))
        if (max != null) put("max_temp", JsonPrimitive(max))
    }))

    private fun draft(
        mode: ThermoMode = ThermoMode.COOL, target: Int? = 70, low: Int? = null, high: Int? = null,
        current: Int? = 72, min: Int = 50, max: Int = 90,
        modes: List<ThermoMode> = listOf(ThermoMode.COOL, ThermoMode.HEAT, ThermoMode.AUTO, ThermoMode.OFF),
    ) = ThermoDraft(modes, mode, target, low, high, current, min, max)

    private fun obj(vararg p: Pair<String, Any>) = JsonObject(p.associate { (k, v) ->
        k to (if (v is Int) JsonPrimitive(v) else JsonPrimitive(v as String))
    })

    @Test fun modes_filteredAndOrdered() {
        val s = climate("cool", modes = listOf("off", "heat_cool", "cool", "heat", "fan_only"))
        assertEquals(listOf(ThermoMode.COOL, ThermoMode.HEAT, ThermoMode.AUTO, ThermoMode.OFF), thermoModes(s))
    }

    @Test fun startDraft_cool() {
        val d = startDraft(climate("cool", temperature = 70.0, current = 72.4))!!
        assertEquals(ThermoMode.COOL, d.mode)
        assertEquals(70, d.target)
        assertEquals(72, d.current)
        assertEquals(50, d.min)
        assertEquals(90, d.max)
    }

    @Test fun startDraft_noTarget_usesCurrentRounded() {
        assertEquals(73, startDraft(climate("off", current = 72.6))!!.target)
        assertEquals(72, startDraft(climate("off"))!!.target)
    }

    @Test fun startDraft_unavailable_null() {
        assertNull(startDraft(null))
        assertNull(startDraft(climate("unavailable")))
        assertNull(startDraft(climate("unknown")))
    }

    @Test fun nudge_coolUpDown_clamped() {
        assertEquals(90, nudgeTemp(draft(target = 90, max = 90), up = true).target)
        assertEquals(71, nudgeTemp(draft(target = 70), up = true).target)
        assertEquals(69, nudgeTemp(draft(target = 70), up = false).target)
        assertEquals(50, nudgeTemp(draft(target = 50, min = 50), up = false).target)
    }

    @Test fun nudge_autoShiftsBoth() {
        val n = nudgeTemp(draft(mode = ThermoMode.AUTO, low = 70, high = 76), up = true)
        assertEquals(71, n.low); assertEquals(77, n.high)
    }

    @Test fun nudge_autoAtMax_unchanged() {
        val d = draft(mode = ThermoMode.AUTO, low = 84, high = 90, max = 90)
        assertEquals(d, nudgeTemp(d, up = true))
    }

    @Test fun nudge_off_unchanged() {
        val d = draft(mode = ThermoMode.OFF)
        assertEquals(d, nudgeTemp(d, up = true))
        assertEquals(d, nudgeTemp(d, up = false))
    }

    @Test fun moveMode_clampsAtEnds() {
        val cool = draft(mode = ThermoMode.COOL)
        assertEquals(ThermoMode.COOL, moveMode(cool, right = false).mode)
        assertEquals(ThermoMode.HEAT, moveMode(cool, right = true).mode)
        val off = draft(mode = ThermoMode.OFF)
        assertEquals(ThermoMode.OFF, moveMode(off, right = true).mode)
        assertEquals(ThermoMode.AUTO, moveMode(off, right = false).mode)
    }

    @Test fun moveMode_standInOff_goesToEnds() {
        val d = draft(mode = ThermoMode.OFF, modes = listOf(ThermoMode.COOL, ThermoMode.HEAT))
        assertEquals(ThermoMode.COOL, moveMode(d, right = true).mode)
        assertEquals(ThermoMode.HEAT, moveMode(d, right = false).mode)
    }

    @Test fun toAuto_withoutRange_makesSixDegreeRange() {
        val n = moveMode(draft(mode = ThermoMode.HEAT, target = 70), right = true)
        assertEquals(ThermoMode.AUTO, n.mode)
        assertEquals(67, n.low); assertEquals(73, n.high)
    }

    @Test fun toCool_fromAuto_usesMidpoint() {
        val d = draft(mode = ThermoMode.AUTO, target = null, low = 70, high = 76)
        val n = moveMode(d, right = false)
        assertEquals(ThermoMode.HEAT, n.mode)
        assertEquals(73, n.target)
    }

    @Test fun call_unchanged_null() {
        val d = draft()
        assertNull(climateCall(d, d))
    }

    @Test fun call_tempOnly_setTemperatureWithMode() {
        val c = climateCall(draft(target = 70), draft(target = 69))!!
        assertEquals("set_temperature", c.service)
        assertEquals(obj("hvac_mode" to "cool", "temperature" to 69), c.data)
    }

    @Test fun call_modeAndTemp_oneCall() {
        val c = climateCall(draft(mode = ThermoMode.COOL, target = 70), draft(mode = ThermoMode.HEAT, target = 68))!!
        assertEquals("set_temperature", c.service)
        assertEquals(obj("hvac_mode" to "heat", "temperature" to 68), c.data)
    }

    @Test fun call_toOff_setHvacMode() {
        val c = climateCall(draft(), draft(mode = ThermoMode.OFF))!!
        assertEquals("set_hvac_mode", c.service)
        assertEquals(obj("hvac_mode" to "off"), c.data)
    }

    @Test fun call_auto_lowHigh() {
        val s = draft(mode = ThermoMode.AUTO, low = 70, high = 76)
        val e = draft(mode = ThermoMode.AUTO, low = 71, high = 77)
        val c = climateCall(s, e)!!
        assertEquals("set_temperature", c.service)
        assertEquals(obj("hvac_mode" to "heat_cool", "target_temp_low" to 71, "target_temp_high" to 77), c.data)
    }

    @Test fun unknownMode_noChange_noCall() {
        val s = startDraft(climate("fan_only", temperature = 70.0, current = 72.0))!!
        assertEquals(ThermoMode.OFF, s.mode)
        assertNull(climateCall(s, s))
        // user nudges: OFF ignores it
        assertNull(climateCall(s, nudgeTemp(s, up = true)))
    }

    @Test fun confirmed_matchesWithinHalfDegree() {
        assertTrue(climateConfirmed(draft(target = 69), climate("cool", temperature = 69.4)))
        assertFalse(climateConfirmed(draft(target = 69), climate("cool", temperature = 70.0)))
        val auto = draft(mode = ThermoMode.AUTO, low = 71, high = 77)
        assertTrue(climateConfirmed(auto, climate("heat_cool", low = 71.0, high = 77.0)))
        assertFalse(climateConfirmed(auto, climate("heat_cool", low = 70.0, high = 77.0)))
        assertTrue(climateConfirmed(draft(mode = ThermoMode.OFF), climate("off")))
    }

    @Test fun confirmed_wrongMode_false() {
        assertFalse(climateConfirmed(draft(mode = ThermoMode.HEAT, target = 70), climate("cool", temperature = 70.0)))
        assertFalse(climateConfirmed(draft(), null))
    }

    @Test fun tileText_allModes() {
        assertEquals("❄ 72° → 70° · Cool", thermostatTileText(climate("cool", temperature = 70.0, current = 72.0)))
        assertEquals("🔥 68° → 70° · Heat", thermostatTileText(climate("heat", temperature = 70.0, current = 68.0)))
        assertEquals("⇅ 72° → 70–76° · Auto",
            thermostatTileText(climate("heat_cool", low = 70.0, high = 76.0, current = 72.0)))
        assertEquals("⏻ 72° · Off", thermostatTileText(climate("off", current = 72.0)))
        assertEquals("72° · Fan only", thermostatTileText(climate("fan_only", current = 72.0)))
        assertEquals("Thermostat unavailable", thermostatTileText(climate("unavailable")))
        assertEquals("Thermostat unavailable", thermostatTileText(null))
        assertNotNull(thermostatTileText(climate("cool")))
    }

    @Test fun sheetTitle_withAndWithoutCurrent() {
        assertEquals("Thermostat · now 72° inside", thermoSheetTitle(draft(current = 72)))
        assertEquals("Thermostat", thermoSheetTitle(draft(current = null)))
    }

    @Test fun sheetTarget_perMode() {
        assertEquals("70°", thermoTargetText(draft(mode = ThermoMode.COOL, target = 70)))
        assertEquals("68°", thermoTargetText(draft(mode = ThermoMode.HEAT, target = 68)))
        assertEquals("70–76°", thermoTargetText(draft(mode = ThermoMode.AUTO, low = 70, high = 76)))
        assertEquals("—", thermoTargetText(draft(mode = ThermoMode.AUTO, low = null, high = 76)))
        assertEquals("—", thermoTargetText(draft(mode = ThermoMode.COOL, target = null)))
        assertEquals("Off", thermoTargetText(draft(mode = ThermoMode.OFF, target = 70)))
    }
}

package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CycleRulesTest {
    private fun st(state: String, unit: String? = null) =
        EntityState(state, JsonObject(unit?.let { mapOf("unit_of_measurement" to JsonPrimitive(it)) } ?: emptyMap()))

    // 2026-11-03T20:00:00Z
    private val now = 1793736000000L
    private val in32 = "2026-11-03T20:32:00+00:00"

    @Test fun timestampRemaining_withTotal() {
        val p = cycleProgress(st("running"), st(in32), st("64", "min"), now)!!
        assertEquals(32, p.minutesLeft)
        assertEquals(0.5f, p.fraction!!, 1e-4f)
    }

    @Test fun partialMinuteRoundsUp() {
        val p = cycleProgress(st("running"), st("2026-11-03T20:00:20+00:00"), null, now)!!
        assertEquals(1, p.minutesLeft)
        assertNull(p.fraction)
    }

    @Test fun numericRemaining_minutesAndHours() {
        assertEquals(45, cycleProgress(null, st("45", "min"), null, now)!!.minutesLeft)
        assertEquals(90, cycleProgress(null, st("1.5", "h"), null, now)!!.minutesLeft)
        assertEquals(2, cycleProgress(null, st("90", "s"), null, now)!!.minutesLeft)
    }

    @Test fun idleStatus_hidesEvenWithAFutureEndTime() {
        // LG keeps the last end time after the cycle ends (dryer "end" at 00:00Z with remaining 01:01Z, 11-03).
        for (s in listOf("end", "power_off", "off", "initial", "sleep", "idle", "standby", "unavailable", "unknown")) {
            assertNull(s, cycleProgress(st(s), st(in32), st("64", "min"), now))
        }
    }

    @Test fun finishedOrMissing_isNull() {
        assertNull(cycleProgress(st("running"), st("2026-11-03T19:59:00+00:00"), st("64"), now))
        assertNull(cycleProgress(st("running"), st("0", "min"), null, now))
        assertNull(cycleProgress(st("running"), st("unknown"), st("64"), now))
        assertNull(cycleProgress(st("running"), null, st("64"), now))
        assertNull(cycleProgress(st("running"), st("garbage"), null, now))
    }

    @Test fun badTotal_noFraction() {
        assertNull(cycleProgress(st("running"), st(in32), st("0"), now)!!.fraction)
        assertNull(cycleProgress(st("running"), st(in32), st("unknown"), now)!!.fraction)
        // Left longer than the total (a stale total): no bar rather than a wrong one.
        assertNull(cycleProgress(st("running"), st(in32), st("20", "min"), now)!!.fraction)
    }

    @Test fun text() {
        assertEquals("32 min left", cycleLeftText(32))
        assertEquals("1 h 5 min left", cycleLeftText(65))
        assertEquals("2 h left", cycleLeftText(120))
    }
}

class CycleTileTextTest {
    private val tile = Tile("switch.washer_power", "Washer Power", "switch", false)
    @Test fun runningCycle_showsTimeLeft() =
        assertEquals("32 min left", tileStateLine(TileUi(tile, true, false, null, false, false, CycleProgress(32, 0.5f))))
    @Test fun noCycle_onOff() = assertEquals("Off", tileStateLine(TileUi(tile, false, false, null, false, false)))
    @Test fun unavailableWins() =
        assertEquals("Unavailable", tileStateLine(TileUi(tile, true, true, null, false, false, CycleProgress(5, null))))
}

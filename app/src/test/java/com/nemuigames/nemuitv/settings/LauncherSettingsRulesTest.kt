package com.nemuigames.nemuitv.settings

import com.nemuigames.nemuitv.ambient.TimerWrite
import com.nemuigames.nemuitv.config.WallpaperOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherSettingsRulesTest {
    @Test fun defaults_includeAmbient() {
        val d = LauncherPrefs.DEFAULT
        assertEquals(5, d.ambientAfterMin); assertEquals(30, d.blackAfterMin); assertEquals(240, d.sleepAfterMin); assertTrue(d.lofiOn)
    }

    @Test fun formatTemp_none_f_c_conversions() {
        // None = today: the number as HA sends it, no unit.
        assertEquals("72°", formatTemp(72.4, "°F", TempUnit.NONE))
        assertEquals("22°", formatTemp(21.6, "°C", TempUnit.NONE))
        // Same unit: no conversion, unit shown.
        assertEquals("72°F", formatTemp(72.4, "°F", TempUnit.F))
        assertEquals("22°C", formatTemp(21.6, "°C", TempUnit.C))
        // Different unit: converted and rounded.
        assertEquals("22°C", formatTemp(72.0, "°F", TempUnit.C))
        assertEquals("-18°C", formatTemp(0.0, "°F", TempUnit.C))
        assertEquals("72°F", formatTemp(22.0, "°C", TempUnit.F))
        assertEquals("32°F", formatTemp(0.0, "°C", TempUnit.F))
        // HA gave no unit ("°"): nothing to convert from, just label it.
        assertEquals("70°F", formatTemp(70.0, "°", TempUnit.F))
    }

    @Test fun timer_null_whenOff() {
        assertNull(timerPeriodMs(LauncherPrefs.DEFAULT))
        assertNull(timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperEveryMin = null)))
        assertEquals(5 * 60_000L, timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperEveryMin = 5)))
        assertEquals(30 * 60_000L, timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperEveryMin = 30)))
    }

    @Test fun advanceOnLeave_iffReturnHomeOn() {
        // "New wallpaper on return Home" On: leaving Home picks the next video — with or without a timer.
        assertTrue(advanceOnLeave(LauncherPrefs.DEFAULT))
        for (n in listOf(5, 15, 30)) assertTrue(advanceOnLeave(LauncherPrefs.DEFAULT.copy(wallpaperEveryMin = n)))
        // Off: only the timer (or Next wallpaper) changes it.
        val off = LauncherPrefs.DEFAULT.copy(wallpaperOnReturn = false)
        assertFalse(advanceOnLeave(off))
        assertFalse(advanceOnLeave(off.copy(wallpaperEveryMin = 15)))
    }

    @Test fun `timer iffIntervalSet independentOfReturnHome`() {
        assertNull(timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperOnReturn = false)))
        assertEquals(15 * 60_000L, timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperEveryMin = 15))) // both on
        assertEquals(15 * 60_000L, timerPeriodMs(LauncherPrefs.DEFAULT.copy(wallpaperOnReturn = false, wallpaperEveryMin = 15)))
    }

    @Test fun `migration fromSingleWallpaperChangeSetting`() {
        // Nothing saved, or the old "Each return Home" (stored as no value): On, timer Off.
        assertEquals(WallpaperChange(onReturn = true, everyMin = null), wallpaperChangeFromStore(null, null, legacyEveryMin = null))
        // Old "Every N min": that's what they chose — return Home Off, timer N.
        for (n in listOf(5, 15, 30)) {
            assertEquals(WallpaperChange(onReturn = false, everyMin = n), wallpaperChangeFromStore(null, null, legacyEveryMin = n))
        }
        // Old value that was never a choice: defaults.
        assertEquals(WallpaperChange(onReturn = true, everyMin = null), wallpaperChangeFromStore(null, null, legacyEveryMin = 7))
        // Already saved in the new form: the old key is ignored.
        assertEquals(WallpaperChange(onReturn = true, everyMin = 5), wallpaperChangeFromStore(true, 5, legacyEveryMin = 30))
        assertEquals(WallpaperChange(onReturn = false, everyMin = null), wallpaperChangeFromStore(false, null, legacyEveryMin = 30))
        assertEquals(WallpaperChange(onReturn = true, everyMin = null), wallpaperChangeFromStore(true, 7, legacyEveryMin = null))
    }

    @Test fun timerChange_restartsSingleTimer() {
        val five = 5 * 60_000L
        val fifteen = 15 * 60_000L
        // Running: next change one period from now.
        assertEquals(1_000L + five, nextRotationAt(now = 1_000L, periodMs = five, homeVisible = true))
        // Changed later: restarts from the change, with the new period (not the old schedule).
        assertEquals(200_000L + fifteen, nextRotationAt(now = 200_000L, periodMs = fifteen, homeVisible = true))
        // Each return Home, or Home hidden: no timer.
        assertNull(nextRotationAt(now = 1_000L, periodMs = null, homeVisible = true))
        assertNull(nextRotationAt(now = 1_000L, periodMs = five, homeVisible = false))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun rotationTimer_neverTwoTimers() {
        val scope = TestScope(StandardTestDispatcher())
        var ticks = 0
        val timer = RotationTimer(scope) { ticks++ }
        timer.restart(periodMs = 1_000L, homeVisible = true)
        timer.restart(periodMs = 1_000L, homeVisible = true) // e.g. setting changed: replaces, never adds
        scope.advanceTimeBy(3_500L); scope.runCurrent()
        assertEquals(3, ticks)
        timer.restart(periodMs = 1_000L, homeVisible = false) // Home left: stopped
        scope.advanceTimeBy(5_000L); scope.runCurrent()
        assertEquals(3, ticks)
        timer.restart(periodMs = 2_000L, homeVisible = true)
        timer.restart(periodMs = null, homeVisible = true) // "Also change every: Off": none
        scope.advanceTimeBy(10_000L); scope.runCurrent()
        assertEquals(3, ticks)
    }

    @Test fun defaults_matchToday() {
        val d = LauncherPrefs.DEFAULT
        assertNull("wallpaper order comes from config", d.wallpaperOrder)
        assertTrue("new wallpaper on each return Home", d.wallpaperOnReturn)
        assertNull("no wallpaper timer", d.wallpaperEveryMin)
        assertEquals(ClockMode.SYSTEM, d.clock)
        assertEquals(TempUnit.NONE, d.unit)
        assertEquals(WallpaperOrder.SEQUENTIAL, d.effectiveOrder(WallpaperOrder.SEQUENTIAL))
        assertEquals(WallpaperOrder.SHUFFLE, d.effectiveOrder(WallpaperOrder.SHUFFLE))
        assertEquals("HH:mm", clockPattern(ClockMode.SYSTEM, system24h = true))
        assertEquals("h:mm a", clockPattern(ClockMode.SYSTEM, system24h = false))
    }

    @Test fun cycles_wrapAround() {
        val seen = mutableListOf<Int?>()
        var every: Int? = null
        repeat(4) { every = nextWallpaperEvery(every); seen += every }
        assertEquals(listOf(5, 15, 30, null), seen)
        assertEquals("Off", everyLabel(null))
        assertEquals("15 min", everyLabel(15))
        assertEquals(WallpaperOrder.SEQUENTIAL, LauncherPrefs.DEFAULT.cycleOrder(WallpaperOrder.SHUFFLE).wallpaperOrder)
        assertEquals(WallpaperOrder.SHUFFLE, LauncherPrefs.DEFAULT.cycleOrder(WallpaperOrder.SEQUENTIAL).wallpaperOrder)
        assertEquals(ClockMode.H12, nextClock(ClockMode.SYSTEM))
        assertEquals(ClockMode.SYSTEM, nextClock(ClockMode.H24))
        assertEquals(TempUnit.F, nextUnit(TempUnit.NONE))
        assertEquals(TempUnit.NONE, nextUnit(TempUnit.C))
        assertEquals("HH:mm", clockPattern(ClockMode.H24, system24h = false))
        assertEquals("h:mm a", clockPattern(ClockMode.H12, system24h = true))
    }

    // The Lofi row (moved from the Control Center's 5th circle, couch round 1 11-02).
    @Test fun lofiLabel_onOff_notSetUp() {
        assertEquals("On", lofiLabel(on = true, setUp = true))
        assertEquals("Off", lofiLabel(on = false, setUp = true))
        assertEquals("Lofi not set up", lofiLabel(on = true, setUp = false))
        assertEquals("Lofi not set up", lofiLabel(on = false, setUp = false))
    }

    @Test fun configProblem_countsIssues() {
        val ok = { issues: List<String> -> com.nemuigames.nemuitv.config.ConfigResult.Ok(com.nemuigames.nemuitv.config.NemuiConfig(ha = null), issues) }
        assertNull(configProblem(ok(emptyList())))
        assertEquals("Config: 1 problem", configProblem(ok(listOf("a"))))
        assertEquals("Config: 2 problems", configProblem(ok(listOf("a", "b"))))
    }

    // No config.json is the normal no-Home-Assistant setup, not a problem.
    @Test fun configProblem_missingIsNotAProblem() {
        assertNull(configProblem(com.nemuigames.nemuitv.config.ConfigResult.Missing))
        assertEquals("Config invalid: bad", configProblem(com.nemuigames.nemuitv.config.ConfigResult.Invalid("bad")))
    }

    @Test fun timerNote_pointsAtSetupOrComputer() {
        assertNull(timerNote(TimerWrite.OK, secureGranted = true))
        assertNull(timerNote(TimerWrite.OK, secureGranted = false))
        assertEquals("Turn on Screensaver in Setup", timerNote(TimerWrite.NOT_SCREENSAVER, secureGranted = true))
        assertEquals("Run setup from a computer", timerNote(TimerWrite.NOT_SCREENSAVER, secureGranted = false))
        assertEquals("Run setup from a computer", timerNote(TimerWrite.NO_PERMISSION, secureGranted = true))
        assertEquals("Run setup from a computer", timerNote(TimerWrite.NO_PERMISSION, secureGranted = false))
    }
}

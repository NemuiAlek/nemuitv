package com.nemuigames.nemuitv.ambient

import com.nemuigames.nemuitv.settings.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTimersTest {
    private val prefs = LauncherPrefs.DEFAULT.copy(ambientAfterMin = 10, sleepAfterMin = -1)
    private val logs = mutableListOf<String>()

    private fun timers(
        canWrite: () -> Boolean = { true },
        screensaver: () -> String? = { "com.nemuigames.nemuitv/.ambient.AmbientDream" },
        putScreenOff: (Int) -> Unit = {},
        putSleep: (Int) -> Unit = {},
    ) = SystemTimers(canWrite, screensaver, putScreenOff, putSleep, pkg = "com.nemuigames.nemuitv", log = { logs += it })

    @Test fun apply_writesExactMs_andNeverIsMinusOne() {
        val written = mutableListOf<Pair<String, Int>>()
        val t = timers(putScreenOff = { written += "off" to it }, putSleep = { written += "sleep" to it })
        assertEquals(TimerWrite.OK, t.apply(prefs))
        assertEquals(listOf("off" to 600000, "sleep" to -1), written)
    }

    @Test fun `apply securityException isNoPermission notCrash`() {
        val t = timers(putSleep = { throw SecurityException("no grant") })
        assertEquals(TimerWrite.NO_PERMISSION, t.apply(prefs))
    }

    @Test fun apply_cannotWriteSettings_writesNothing() {
        var wrote = false
        val t = timers(canWrite = { false }, putScreenOff = { wrote = true }, putSleep = { wrote = true })
        assertEquals(TimerWrite.NO_PERMISSION, t.apply(prefs))
        assertTrue(!wrote)
    }

    // The screensaver was given back to Google: never write our timers over Google's 10/20 min again.
    @Test fun apply_notTheScreensaver_writesNothing() {
        var wrote = false
        val t = timers(
            screensaver = { "com.google.android.apps.tv.dreamx/.service.Backdrop" },
            putScreenOff = { wrote = true }, putSleep = { wrote = true },
        )
        assertEquals(TimerWrite.NOT_SCREENSAVER, t.apply(prefs))
        assertTrue(!wrote)
    }

    // Same class name in another package (e.g. an older build) isn't ours.
    @Test fun apply_otherPackagesDream_writesNothing() {
        var wrote = false
        val t = timers(screensaver = { "com.example.oldtv/.ambient.AmbientDream" }, putScreenOff = { wrote = true }, putSleep = { wrote = true })
        assertEquals(TimerWrite.NOT_SCREENSAVER, t.apply(prefs))
        assertTrue(!wrote)
    }

    @Test fun apply_screensaverUnset_writesNothing() {
        var wrote = false
        val t = timers(screensaver = { null }, putScreenOff = { wrote = true }, putSleep = { wrote = true })
        assertEquals(TimerWrite.NOT_SCREENSAVER, t.apply(prefs))
        assertTrue(!wrote)
    }

    @Test fun `apply oneOfSeveralScreensavers stillWrites`() {
        val t = timers(screensaver = { "a.b/.Other,com.nemuigames.nemuitv/.ambient.AmbientDream" })
        assertEquals(TimerWrite.OK, t.apply(prefs))
    }

    // Every Home resume re-applies; the log only speaks when something changed.
    @Test fun apply_logsOnlyWhenResultChanges() {
        var dream = true
        val t = timers(screensaver = { if (dream) "com.nemuigames.nemuitv/.ambient.AmbientDream" else null })
        repeat(3) { t.apply(prefs) }
        assertEquals(1, logs.size)
        t.apply(prefs.copy(ambientAfterMin = 30))
        assertEquals(2, logs.size)
        dream = false
        repeat(3) { t.apply(prefs) }
        assertEquals(3, logs.size)
    }
}

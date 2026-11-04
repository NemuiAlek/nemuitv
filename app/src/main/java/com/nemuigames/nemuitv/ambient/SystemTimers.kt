package com.nemuigames.nemuitv.ambient

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.nemuigames.nemuitv.settings.LauncherPrefs

/**
 * Result of writing Android's timers. NOT_SCREENSAVER = NemuiTV isn't the screensaver (never turned on, or given back
 * to Google): nothing is written, so Google's own timers stay. The settings page points at the fix for each
 * ([com.nemuigames.nemuitv.settings.timerNote]).
 */
enum class TimerWrite { OK, NO_PERMISSION, NOT_SCREENSAVER }

/**
 * Writes the Ambient/Sleep choices into Android's own timers: Settings.System screen_off_timeout (when the screensaver
 * starts) and Settings.Secure sleep_timeout (when the box sleeps; -1 = Never). Only while NemuiTV's
 * [AmbientDream] is in Settings.Secure screensaver_components (reading it needs no permission). Needs the settings
 * grants scripts/setup.sh gives; without them nothing is written and [apply] says NO_PERMISSION (never throws).
 * Called on every Home resume, so it logs only when the outcome changes.
 */
class SystemTimers(
    private val canWrite: () -> Boolean,
    private val screensaverComponents: () -> String?,
    private val putScreenOff: (Int) -> Unit,
    private val putSleep: (Int) -> Unit,
    private val pkg: String,
    private val log: (String) -> Unit = { Log.i("NemuiTV", it) },
) {
    constructor(context: Context) : this(
        canWrite = { Settings.System.canWrite(context) },
        screensaverComponents = { Settings.Secure.getString(context.contentResolver, "screensaver_components") },
        putScreenOff = { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, it) },
        putSleep = { Settings.Secure.putInt(context.contentResolver, "sleep_timeout", it) },
        pkg = context.packageName,
    )

    private var lastLog: String? = null

    fun apply(prefs: LauncherPrefs): TimerWrite {
        val (result, msg) = write(prefs)
        if (msg != lastLog) { lastLog = msg; log(msg) }
        return result
    }

    private fun write(prefs: LauncherPrefs): Pair<TimerWrite, String> {
        if (!isOurDream(screensaverComponents(), pkg)) {
            return TimerWrite.NOT_SCREENSAVER to "timers: NemuiTV isn't the screensaver; not writing (turn it on in Setup)"
        }
        if (!canWrite()) return TimerWrite.NO_PERMISSION to "timers: no permission (can't write settings)"
        return try {
            val off = screenOffTimeoutMs(prefs).toInt()
            val sleep = sleepTimeoutMs(prefs).toInt()
            putScreenOff(off)
            putSleep(sleep)
            TimerWrite.OK to "timers: screen_off_timeout=$off sleep_timeout=$sleep -> OK"
        } catch (e: SecurityException) {
            TimerWrite.NO_PERMISSION to "timers: no permission (run scripts/setup.sh)"
        } catch (e: RuntimeException) {
            TimerWrite.NO_PERMISSION to "timers: write failed: $e"
        }
    }
}

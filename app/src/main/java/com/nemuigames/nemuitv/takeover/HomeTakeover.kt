package com.nemuigames.nemuitv.takeover

/**
 * Decides what a Home key press means: tap = NemuiTV home, hold = NemuiTV Control Center.
 *
 * The Streamer's remote sends ONE key-down for a held Home (no repeats; release ~1.5 s later), and Google's
 * own quick-settings panel opens on a ~0.5 s timer. So the service posts a timer on the first down
 * ([Action.SCHEDULE_HOLD_CHECK]) and asks [onHoldTimeout] whether the key is still held when it fires.
 * Pure logic so it's unit-tested; [HomeTakeoverService] does the Android side.
 */
class HomeKeyDecider {
    enum class Action { NONE, SCHEDULE_HOLD_CHECK, OPEN_HOME, OPEN_CONTROL_CENTER }
    data class Result(val consume: Boolean, val action: Action)

    private var down = false      // we saw (and swallowed) the start of the current press
    private var holdFired = false // the hold already opened the Control Center (or the press already went Home) for this press

    /**
     * [dreaming] = NemuiTV's screensaver is running: opening NemuiTV alone would land behind it, so the press goes Home
     * right away (the service ends the screensaver first) and the rest of the press does nothing (no hold → Control
     * Center while dreaming).
     */
    fun onDown(repeatCount: Int, dreaming: Boolean = false): Result {
        if (repeatCount > 0) return Result(consume = down, action = Action.NONE) // repeats: swallow only our press
        down = true
        if (dreaming) { holdFired = true; return Result(consume = true, action = Action.OPEN_HOME) }
        holdFired = false
        return Result(consume = true, action = Action.SCHEDULE_HOLD_CHECK)
    }

    fun onHoldTimeout(): Action {
        if (!down || holdFired) return Action.NONE
        holdFired = true
        return Action.OPEN_CONTROL_CENTER
    }

    fun onUp(): Result {
        // A release whose press we never saw belongs to the system (service started mid-press): pass it on.
        if (!down) return Result(consume = false, action = Action.NONE)
        down = false
        return Result(consume = true, action = if (holdFired) Action.NONE else Action.OPEN_HOME)
    }

    companion object { const val HOLD_MS = 450L } // just under Google's ~0.5 s panel timer
}

/** Spots Google's main home screen appearing (Back out of an app, boot) so NemuiTV can replace it. */
object GoogleHomeWatch {
    const val GOOGLE_HOME_PKG = "com.google.android.apps.tv.launcherx"
    const val GOOGLE_HOME_ACTIVITY = "$GOOGLE_HOME_PKG.home.HomeActivity"
    /** The same appearance is often reported twice; one launch is enough. */
    const val DEBOUNCE_MS = 1_000L

    fun shouldReclaim(pkg: CharSequence?, cls: CharSequence?, nowMs: Long, lastLaunchMs: Long): Boolean =
        pkg?.toString() == GOOGLE_HOME_PKG && cls?.toString() == GOOGLE_HOME_ACTIVITY &&
            nowMs - lastLaunchMs >= DEBOUNCE_MS
}

package com.nemuigames.nemuitv.ambient

import android.view.KeyEvent
import com.nemuigames.nemuitv.ha.TvState
import com.nemuigames.nemuitv.settings.LauncherPrefs
import com.nemuigames.nemuitv.settings.after

/** "Ambient after" (screensaver starts), OK order. Becomes Settings.System SCREEN_OFF_TIMEOUT. */
val AMBIENT_AFTER_CHOICES = listOf(5, 10, 30)

/** "Black after" (minutes from ambient start until the screen fades to black; NemuiTV-only). */
val BLACK_AFTER_CHOICES = listOf(30, 60, 90)

/** "Sleep after" in minutes; -1 = Never. Becomes Settings.Secure sleep_timeout. */
val SLEEP_AFTER_CHOICES = listOf(120, 240, 480, -1)

fun nextAmbientAfter(current: Int): Int = AMBIENT_AFTER_CHOICES.after(current)
fun nextBlackAfter(current: Int): Int = BLACK_AFTER_CHOICES.after(current)
fun nextSleepAfter(current: Int): Int = SLEEP_AFTER_CHOICES.after(current)

/** 5 -> "5 min", 120 -> "2 h", -1 -> "Never". */
fun minutesLabel(min: Int): String = when {
    min == -1 -> "Never"
    min >= 60 && min % 60 == 0 -> "${min / 60} h"
    else -> "$min min"
}

fun screenOffTimeoutMs(prefs: LauncherPrefs): Long = prefs.ambientAfterMin * 60_000L

/** Never -> -1 (never 0: 0 can mean "sleep immediately"). */
fun sleepTimeoutMs(prefs: LauncherPrefs): Long = if (prefs.sleepAfterMin == -1) -1L else prefs.sleepAfterMin * 60_000L

/** True when the TV would go to sleep before the black fade starts. Never = false. */
fun sleepsBeforeBlack(prefs: LauncherPrefs): Boolean =
    prefs.sleepAfterMin != -1 && prefs.sleepAfterMin < prefs.ambientAfterMin + prefs.blackAfterMin

// Serpentine over x in {0,8,16,24}, y in {0,8,16}: row 0 left->right, row 1 right->left, row 2 left->right.
private val SHIFT_PATH: List<Pair<Int, Int>> = buildList {
    for (row in 0..2) {
        val xs = if (row % 2 == 0) 0..3 else 3 downTo 0
        for (c in xs) add(c * 8 to row * 8)
    }
}

// Forward path, then back reversed without repeating either end: 12 + 10 = 22 steps.
private val SHIFT_CYCLE: List<Pair<Int, Int>> = SHIFT_PATH + SHIFT_PATH.reversed().drop(1).dropLast(1)

/** Burn-in shift offset (dp) for [step]; every move is one 8 dp stride, cycle length 22. */
fun shiftOffsetDp(step: Int): Pair<Int, Int> = SHIFT_CYCLE[step.mod(SHIFT_CYCLE.size)]

/** One shift step every 3 minutes of ambient time. */
fun shiftStep(elapsedMs: Long): Int = (elapsedMs / 180_000).toInt()

fun isBlack(elapsedMs: Long, blackAfterMin: Int): Boolean = elapsedMs >= blackAfterMin * 60_000L

/**
 * The Now Playing banner shows in ambient only while music actually plays ([playing] = the session's state, null =
 * nothing), never in black. Paused → no banner: it would sit still.
 */
fun ambientBannerShown(playing: Boolean?, black: Boolean): Boolean = playing == true && !black

/** The banner's shift (dp): the clock's [shiftOffsetDp] mirrored horizontally (it sits in the top-right corner). */
fun bannerShiftDp(step: Int): Pair<Int, Int> = shiftOffsetDp(step).let { (x, y) -> -x to y }

/**
 * Lofi starts only from Home, with the Lofi switch on and lofi configured, when nothing on the box is PLAYING, the TV is
 * showing this box ([tvShowsThisBox]) and the Spotify account isn't already playing somewhere ([accountPlaying]).
 * Never replace what's playing.
 */
fun shouldStartLofi(
    wasHome: Boolean, lofiOn: Boolean, anyPlaying: Boolean, configured: Boolean,
    tvShowsThisBox: Boolean, accountPlaying: Boolean,
): Boolean = wasHome && lofiOn && !anyPlaying && configured && tvShowsThisBox && !accountPlaying

/**
 * The TV is on this box's input. TV or its `thisDevice` not configured → true (nothing to check, don't block).
 * Configured but HA doesn't know (no state, unavailable, no source) → false.
 */
fun tvShowsThisBox(tvEntity: String?, thisDevice: String?, tv: TvState?): Boolean =
    tvEntity == null || thisDevice == null || (tv != null && tv.available && tv.source == thisDevice)

/** HA's Spotify entity says the account is playing (anywhere). Unknown / null / paused → false (don't block). */
fun accountPlaying(spotifyState: String?): Boolean = spotifyState == "playing"

/** Home counts as "in front" if it is resumed, or paused within the last 3 s (the dream pauses it as it starts). */
fun wasHomeBeforeDream(homeResumed: Boolean, homePausedAtMs: Long?, nowMs: Long): Boolean =
    homeResumed || (homePausedAtMs != null && nowMs - homePausedAtMs <= 3_000L)

/** Now Playing can only see sessions with the notification-listener grant; without it, assume something may play. */
fun mediaMayBePlaying(listening: Boolean, anyPlaying: Boolean): Boolean = !listening || anyPlaying

/**
 * The video the screensaver starts on. Home just paused for the dream → what it showed then ([atHomePause]; its onStop
 * may already have advanced [current]). Home still resumed (not advanced yet) or the dream is over an app → [current].
 */
fun ambientStartWallpaper(homeResumed: Boolean, homePausedAtMs: Long?, nowMs: Long, atHomePause: String?, current: String?): String? =
    if (!homeResumed && wasHomeBeforeDream(false, homePausedAtMs, nowMs)) atHomePause ?: current else current


enum class AmbientKeyAction { PREVIOUS, NEXT, IGNORE, WAKE }

/**
 * A remote key in the screensaver: ◀ ▶ while music plays change the song (first down
 * only; held = repeats and the release do nothing), black or not. Any other key — or ◀ ▶ while nothing plays — ends the
 * screensaver. Every key is consumed either way (it never reaches what's underneath).
 */
fun ambientKey(keyCode: Int, repeatCount: Int, down: Boolean, playing: Boolean): AmbientKeyAction {
    val left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
    if (!left && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return AmbientKeyAction.WAKE
    if (!down || repeatCount > 0) return AmbientKeyAction.IGNORE
    if (!playing) return AmbientKeyAction.WAKE
    return if (left) AmbientKeyAction.PREVIOUS else AmbientKeyAction.NEXT
}

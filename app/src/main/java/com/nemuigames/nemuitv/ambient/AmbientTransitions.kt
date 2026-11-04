package com.nemuigames.nemuitv.ambient

/*
 * Fades between Home and the screensaver (couch round 2, 11-02). Each side plays its own wallpaper video, so the switch
 * (new player, video restarts) would flicker; it happens under black instead: fade to black, switch, fade in.
 *  - Start: the dream window starts translucent and fades a black layer over the still-visible Home, turns opaque (Home
 *    stops, freeing its decoder), starts its own wallpaper and fades in.
 *  - Exit (any wake): the scene fades to black, then the dream finishes. Home coming back starts under a black cover
 *    that lifts once its wallpaper is up.
 */

/** Home fading out under the translucent dream window. */
const val AMBIENT_START_FADE_OUT_MS = 400
/** The screensaver fading in from black. */
const val AMBIENT_FADE_IN_MS = 800
/** The screensaver fading to black before it finishes (Android allows ~5 s). */
const val AMBIENT_EXIT_FADE_MS = 400
/** Home's black cover lifting after the screensaver. */
const val HOME_COVER_FADE_MS = 400

/** Home (re)starting counts as "after the screensaver" while it's still up or ended at most 2 s ago. */
fun homeCoverOnStart(dreaming: Boolean, dreamEndedAtMs: Long?, nowMs: Long): Boolean =
    dreaming || (dreamEndedAtMs != null && nowMs - dreamEndedAtMs <= 2_000L)

/** Home's cover lifts once the screensaver is gone and the wallpaper (poster / first frame) is up; 1 s at most. */
fun homeCoverLifts(dreaming: Boolean, wallpaperReady: Boolean, msSinceDreamEnd: Long): Boolean =
    !dreaming && (wallpaperReady || msSinceDreamEnd >= 1_000L)

/** The screensaver's wallpaper starts once Home is fully hidden (its decoder freed), 1 s after going opaque at most. */
fun ambientWallpaperMayStart(homeStarted: Boolean, msSinceOpaque: Long): Boolean = !homeStarted || msSinceOpaque >= 1_000L

/** The screensaver fades in once its wallpaper is up, 1.5 s after going opaque at most. */
fun ambientRevealed(wallpaperReady: Boolean, msSinceOpaque: Long): Boolean = wallpaperReady || msSinceOpaque >= 1_500L

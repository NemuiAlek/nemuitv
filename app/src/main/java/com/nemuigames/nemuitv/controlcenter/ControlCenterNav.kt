package com.nemuigames.nemuitv.controlcenter

import com.nemuigames.nemuitv.home.DockKey

/**
 * The Control Center's focus targets on the main page: the Now Playing banner left of the column (only while
 * something is playing or paused recently), then the column of icon circles (three without Inputs), top to bottom. SETTINGS opens the
 * Launcher settings sub-page (Google's Settings moved in there as "Advanced settings"; the Lofi switch too, couch
 * round 1 11-02).
 */
enum class CcTile { BANNER, INPUTS, WIFI, NEXT_WALLPAPER, SETTINGS }

/**
 * Where the main page puts focus when it appears: back on [returnTo] (the tile that opened the sub-page, or the banner
 * after the player closes), else the first circle (Inputs when shown). The banner is hidden when nothing is playing, so it's never a target then.
 */
fun ccMainFocus(returnTo: CcTile?, bannerShown: Boolean, inputsShown: Boolean = true): CcTile {
    val first = if (inputsShown) CcTile.INPUTS else CcTile.WIFI // Inputs is left out without a TV + HA setup
    return when {
        returnTo == CcTile.BANNER && !bannerShown -> first
        returnTo == CcTile.INPUTS && !inputsShown -> first
        returnTo != null -> returnTo
        else -> first
    }
}

/**
 * What the panel shows: the tile grid, or one of its sub-pages. WALLPAPER_SETTINGS and SCREENSAVER_SETTINGS are
 * buckets inside Launcher settings (the list had grown to 13 rows); SETUP is the setup checklist and UPDATE the update page, also in there.
 */
enum class CcPage { MAIN, INPUTS, WIFI_QR, LAUNCHER_SETTINGS, WALLPAPER_SETTINGS, SCREENSAVER_SETTINGS, SETUP, UPDATE }

/** Where Back goes from this page: a Launcher settings bucket → Launcher settings, any other sub-page → main. */
val CcPage.parent: CcPage get() = when (this) {
    CcPage.WALLPAPER_SETTINGS, CcPage.SCREENSAVER_SETTINGS, CcPage.SETUP, CcPage.UPDATE -> CcPage.LAUNCHER_SETTINGS
    else -> CcPage.MAIN
}

sealed interface CcAction {
    /** Swallow the key, do nothing. */
    data object None : CcAction
    data class Open(val page: CcPage) : CcAction
    /** Sub-page → main page. */
    data object Back : CcAction
    /** Close the whole panel. */
    data object Close : CcAction
    /** Not ours: Compose moves focus / clicks as usual. */
    data object PassThrough : CcAction
}

/**
 * Remote keys in the Control Center. Back acts on release (like the Home-button service, so whatever is underneath
 * never sees half a press): sub-page → its [parent], main page → close. Everything else passes through (the arrows move
 * focus between tiles; no tile captures them).
 */
fun ccKey(page: CcPage, key: DockKey, down: Boolean): CcAction {
    if (key != DockKey.BACK) return CcAction.PassThrough
    return when {
        down -> CcAction.None
        page == CcPage.MAIN -> CcAction.Close
        else -> CcAction.Back
    }
}

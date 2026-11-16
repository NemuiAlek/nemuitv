package com.nemuigames.nemuitv.controlcenter

import com.nemuigames.nemuitv.home.DockKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControlCenterNavTest {
    @Test fun back_subPage_returnsMain() {
        for (page in CcPage.entries - CcPage.MAIN) {
            assertEquals(CcAction.None, ccKey(page, DockKey.BACK, down = true))
            assertEquals(CcAction.Back, ccKey(page, DockKey.BACK, down = false))
        }
    }

    @Test fun updateParentIsLauncherSettings() = assertEquals(CcPage.LAUNCHER_SETTINGS, CcPage.UPDATE.parent)

    @Test fun back_main_closes() {
        assertEquals(CcAction.None, ccKey(CcPage.MAIN, DockKey.BACK, down = true))
        assertEquals(CcAction.Close, ccKey(CcPage.MAIN, DockKey.BACK, down = false))
    }

    @Test fun arrowsAndOk_alwaysPassThrough() {
        // No tile captures ◀ ▶ any more (the Volume tile did, and blocked moving between tiles).
        for (page in CcPage.entries) for (key in listOf(DockKey.LEFT, DockKey.RIGHT, DockKey.UP, DockKey.DOWN, DockKey.OK)) {
            assertEquals("$page $key", CcAction.PassThrough, ccKey(page, key, down = true))
            assertEquals("$page $key up", CcAction.PassThrough, ccKey(page, key, down = false))
        }
    }

    // No Volume tile, no Lofi circle (moved to Launcher settings 11-02); one Settings circle (opens Launcher settings,
    // which holds "Advanced settings"). BANNER = the Now Playing banner beside the column (was the NOW_PLAYING tile).
    @Test fun bannerThenFourCircles() = assertEquals(
        listOf("BANNER", "INPUTS", "WIFI", "NEXT_WALLPAPER", "SETTINGS"),
        CcTile.entries.map { it.name },
    )

    // Where focus lands on the main page: always the bare column, first focus Inputs.
    @Test fun mainFocus_default_isInputs() { assertEquals(CcTile.INPUTS, ccMainFocus(null, bannerShown = true)); assertEquals(CcTile.INPUTS, ccMainFocus(null, bannerShown = false)) }
    @Test fun mainFocus_returnsToOpener() = assertEquals(CcTile.WIFI, ccMainFocus(CcTile.WIFI, bannerShown = false))
    @Test fun mainFocus_bannerGone_inputs() = assertEquals(CcTile.INPUTS, ccMainFocus(CcTile.BANNER, bannerShown = false))
    @Test fun mainFocus_noInputs_firstIsWifi() {
        assertEquals(CcTile.WIFI, ccMainFocus(null, bannerShown = false, inputsShown = false))
        assertEquals(CcTile.WIFI, ccMainFocus(CcTile.BANNER, bannerShown = false, inputsShown = false))
        assertEquals(CcTile.WIFI, ccMainFocus(CcTile.INPUTS, bannerShown = false, inputsShown = false))
        assertEquals(CcTile.SETTINGS, ccMainFocus(CcTile.SETTINGS, bannerShown = false, inputsShown = false))
    }
    @Test fun mainFocus_bannerShown_returnsToBanner() = assertEquals(CcTile.BANNER, ccMainFocus(CcTile.BANNER, bannerShown = true))
    @Test fun back_withPlayerOpen_closesPlayerOnly() { val s = ControlCenterState(); s.playerOpen = true; assertFalse(s.back()); assertFalse(s.playerOpen) }

    // Launcher settings buckets: Wallpaper › and Screensaver › are sub-pages of Launcher settings.
    @Test fun `parent bucketsGoToLauncherSettings othersToMain`() {
        assertEquals(CcPage.LAUNCHER_SETTINGS, CcPage.WALLPAPER_SETTINGS.parent)
        assertEquals(CcPage.LAUNCHER_SETTINGS, CcPage.SCREENSAVER_SETTINGS.parent)
        for (page in listOf(CcPage.INPUTS, CcPage.WIFI_QR, CcPage.LAUNCHER_SETTINGS)) assertEquals(CcPage.MAIN, page.parent)
    }

    @Test fun `back fromBucket returnsToLauncherSettings rememberingTheBucket`() {
        val s = ControlCenterState()
        s.open(CcPage.LAUNCHER_SETTINGS)
        s.open(CcPage.SCREENSAVER_SETTINGS)
        assertEquals(false, s.back())
        assertEquals(CcPage.LAUNCHER_SETTINGS, s.page)
        assertEquals(CcPage.SCREENSAVER_SETTINGS, s.cameFrom)
        assertEquals(false, s.back())
        assertEquals(CcPage.MAIN, s.page)
        assertEquals(false, s.back()) // main page = the column: Back starts its fold
        assertEquals(true, s.closing)
    }

    @Test fun `openingLauncherSettingsFresh forgetsTheBucket`() {
        val s = ControlCenterState()
        s.open(CcPage.LAUNCHER_SETTINGS); s.open(CcPage.WALLPAPER_SETTINGS); s.back(); s.back()
        s.open(CcPage.LAUNCHER_SETTINGS)
        assertEquals(null, s.cameFrom)
    }

    // The main page is always the bare column: Back folds it up first; the panel hides itself when the fold ends.
    @Test fun back_main_startsFold_doesNotHideYet() {
        val s = ControlCenterState()
        assertEquals(false, s.back())
        assertEquals(true, s.closing)
    }

    @Test fun back_playerOpen_thenBackAgain_folds() {
        val s = ControlCenterState(); s.playerOpen = true
        assertEquals(false, s.back())
        assertEquals(false, s.closing)
        assertEquals(false, s.back())
        assertEquals(true, s.closing)
    }

    @Test fun back_onSubPage_goesToParent() {
        val s = ControlCenterState()
        s.open(CcPage.LAUNCHER_SETTINGS)
        assertEquals(false, s.back())
        assertEquals(CcPage.MAIN, s.page)
        assertEquals(false, s.closing)
    }
}

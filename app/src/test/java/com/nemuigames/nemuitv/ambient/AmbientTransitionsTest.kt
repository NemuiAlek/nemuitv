package com.nemuigames.nemuitv.ambient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fades between Home and the screensaver (couch round 2, 11-02): the wallpaper video switch is hidden under black.
class AmbientTransitionsTest {
    // Home coming back right after the screensaver starts under black; Home opened any other time doesn't.
    @Test fun homeCover_whileDreamStillUp() = assertTrue(homeCoverOnStart(dreaming = true, dreamEndedAtMs = null, nowMs = 10_000))
    @Test fun homeCover_justAfterDreamEnded() = assertTrue(homeCoverOnStart(false, dreamEndedAtMs = 9_000, nowMs = 10_000))
    @Test fun homeCover_notLongAfterDreamEnded() = assertFalse(homeCoverOnStart(false, dreamEndedAtMs = 5_000, nowMs = 10_000))
    @Test fun homeCover_neverDreamed() = assertFalse(homeCoverOnStart(false, dreamEndedAtMs = null, nowMs = 10_000))

    // The cover lifts once the screensaver is gone and Home's wallpaper is up — or after 1 s anyway (never stays black).
    @Test fun homeCoverLifts_notWhileDreaming() = assertFalse(homeCoverLifts(dreaming = true, wallpaperReady = true, msSinceDreamEnd = 0))
    @Test fun homeCoverLifts_whenWallpaperReady() = assertTrue(homeCoverLifts(false, wallpaperReady = true, msSinceDreamEnd = 0))
    @Test fun homeCoverLifts_waitsForWallpaper() = assertFalse(homeCoverLifts(false, wallpaperReady = false, msSinceDreamEnd = 900))
    @Test fun homeCoverLifts_cappedAt1s() = assertTrue(homeCoverLifts(false, wallpaperReady = false, msSinceDreamEnd = 1_000))

    // The screensaver's own wallpaper waits for Home to be fully hidden (its video decoder freed), at most 1 s.
    @Test fun ambientWallpaper_waitsForHomeToStop() = assertFalse(ambientWallpaperMayStart(homeStarted = true, msSinceOpaque = 500))
    @Test fun ambientWallpaper_startsOnceHomeStopped() = assertTrue(ambientWallpaperMayStart(homeStarted = false, msSinceOpaque = 0))
    @Test fun ambientWallpaper_cappedAt1s() = assertTrue(ambientWallpaperMayStart(homeStarted = true, msSinceOpaque = 1_000))

    // The scene fades in once its wallpaper (poster) is up, or after 1.5 s anyway.
    @Test fun ambientReveal_whenWallpaperReady() = assertTrue(ambientRevealed(wallpaperReady = true, msSinceOpaque = 100))
    @Test fun ambientReveal_waits() = assertFalse(ambientRevealed(wallpaperReady = false, msSinceOpaque = 1_400))
    @Test fun ambientReveal_cappedAt1500ms() = assertTrue(ambientRevealed(wallpaperReady = false, msSinceOpaque = 1_500))
}

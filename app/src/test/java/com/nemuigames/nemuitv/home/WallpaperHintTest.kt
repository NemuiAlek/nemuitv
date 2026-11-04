package com.nemuigames.nemuitv.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperHintTest {
    @Test fun showsWhenNoWallpapersAndNotDismissed() = assertTrue(showWallpaperHint(0, false, false))
    @Test fun hiddenOnceThereIsAWallpaper() = assertFalse(showWallpaperHint(1, false, false))
    @Test fun hiddenAfterDismissing() = assertFalse(showWallpaperHint(0, true, false))
    @Test fun hiddenWhileTheChecklistIsOpen() = assertFalse(showWallpaperHint(0, false, true))
}

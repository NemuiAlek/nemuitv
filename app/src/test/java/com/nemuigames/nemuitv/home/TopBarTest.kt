package com.nemuigames.nemuitv.home

import com.nemuigames.nemuitv.nowplaying.NowPlayingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarTest {
    private fun np(title: String?, artist: String?) = NowPlayingState("p", "Spotify", title, artist, null, true)

    @Test fun upGoesToBannerOnlyWhenShowing() {
        assertEquals(TopBarTarget.BANNER, upFromDockTarget(true))
        assertEquals(TopBarTarget.CONTROL_CENTER, upFromDockTarget(false))
    }
    // Two lines: title over artist.
    @Test fun titleAndArtistLines() {
        assertEquals("Song", bannerTitle(np("Song", "Band")))
        assertEquals("Band", bannerArtist(np("Song", "Band")))
    }
    @Test fun titleFallsBackToAppLabel() = assertEquals("Spotify", bannerTitle(np(null, null)))
    @Test fun blankTitleFallsBackToAppLabel() = assertEquals("Spotify", bannerTitle(np(" ", "Band")))
    @Test fun artistLineOmittedWhenBlankOrMissing() {
        assertEquals(null, bannerArtist(np("Song", " ")))
        assertEquals(null, bannerArtist(np("Song", null)))
    }
    @Test fun ccButtonHidden_whenOverlayShowing() {
        assertTrue(topRowHidden(panelOpen = false, overlayShowing = true))
        assertTrue(topRowHidden(panelOpen = true, overlayShowing = false))
        assertFalse(topRowHidden(panelOpen = false, overlayShowing = false))
    }
}

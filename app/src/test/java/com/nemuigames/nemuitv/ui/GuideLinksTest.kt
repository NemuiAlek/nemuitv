package com.nemuigames.nemuitv.ui

import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.hub.hubShowsNoHaPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLinksTest {
    @Test fun urls() {
        assertEquals("https://github.com/NemuiAlek/nemuitv", GuideLinks.REPO)
        assertEquals("https://github.com/NemuiAlek/nemuitv/blob/main/docs/home-assistant.md", GuideLinks.HA)
        assertEquals("https://github.com/NemuiAlek/nemuitv/blob/main/docs/wallpapers.md", GuideLinks.WALLPAPERS)
        assertEquals("https://github.com/NemuiAlek/nemuitv#quick-start", GuideLinks.SETUP)
    }

    @Test fun noHaPageOnlyWhenNotConfigured() {
        assertTrue(hubShowsNoHaPage(HaStatus.NOT_CONFIGURED))
        for (s in HaStatus.values().filter { it != HaStatus.NOT_CONFIGURED }) assertFalse(hubShowsNoHaPage(s))
    }
}

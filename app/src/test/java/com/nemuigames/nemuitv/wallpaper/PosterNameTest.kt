package com.nemuigames.nemuitv.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterNameTest {
    @Test fun sameVideo_sameName() =
        assertEquals(posterName("Hand hold.mp4", 100, 5), posterName("Hand hold.mp4", 100, 5))

    @Test fun replacedVideo_newName() {
        assertNotEquals(posterName("a.mp4", 100, 5), posterName("a.mp4", 101, 5)) // different size
        assertNotEquals(posterName("a.mp4", 100, 5), posterName("a.mp4", 100, 6)) // re-pushed
        assertNotEquals(posterName("a.mp4", 100, 5), posterName("b.mp4", 100, 5))
    }

    @Test fun `nameIsFileSystemSafe evenForOddVideoNames`() {
        val n = posterName("Snowfall in Forest   FREE \$`'\"/ 4K.mp4", 1, 1)
        assertTrue(n, n.matches(Regex("[0-9a-f]{40}\\.jpg")))
    }
}

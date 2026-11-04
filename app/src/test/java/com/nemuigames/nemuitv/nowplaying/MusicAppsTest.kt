package com.nemuigames.nemuitv.nowplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Now Playing shows music apps only: YouTube gives a video title at best, Netflix nothing.
class MusicAppsTest {
    @Test fun spotifyAndYouTubeMusic_areMusic() {
        assertTrue(isMusicApp("com.spotify.tv.android"))
        assertTrue(isMusicApp("com.google.android.youtube.tvmusic"))
    }

    @Test fun videoApps_areNot() {
        for (pkg in listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom"))
            assertFalse(pkg, isMusicApp(pkg))
    }
}

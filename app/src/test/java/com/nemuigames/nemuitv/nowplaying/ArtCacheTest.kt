package com.nemuigames.nemuitv.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ArtCacheTest {
    /** Stands in for an android.graphics.Bitmap: no equals override, so keys compare it by identity. */
    private class FakeBitmap(val pixels: String)

    private fun key(src: Any?) = ArtKey(pkg = "spotify", src = src)

    @Test fun `sameSourceBitmap reusesValue withoutScalingAgain`() {
        // Playback-state refreshes re-read the same metadata object → the same bitmap → a hit.
        val cache = SingleSlotCache<ArtKey, Any>()
        val src = FakeBitmap("a")
        var made = 0
        val a = cache.get(key(src)) { made++; Any() }
        val b = cache.get(key(src)) { made++; Any() }
        assertSame(a, b)
        assertEquals(1, made)
    }

    @Test fun sameText_newArt_givesNewBitmap() {
        // The Spotify bug: the new track's text arrives with the previous art, then the new art with the SAME text.
        // The art key no longer contains the text, so the second update (a new source bitmap) must be scaled anew.
        val cache = SingleSlotCache<ArtKey, String>()
        val oldArt = FakeBitmap("old")
        val newArt = FakeBitmap("new")
        assertEquals("old", cache.get(key(oldArt)) { oldArt.pixels })
        assertEquals("new", cache.get(key(newArt)) { newArt.pixels })
    }

    @Test fun artArrivingLater_isPickedUp() {
        val cache = SingleSlotCache<ArtKey, String?>()
        assertEquals(null, cache.get(key(null)) { null })
        assertEquals("art", cache.get(key(FakeBitmap("art"))) { "art" })
    }

    @Test fun `sameArtResentInNewBitmap keepsCachedValue`() {
        // A new metadata object with identical artwork: scaled again, but the cached value is kept (equal states).
        val cache = SingleSlotCache<ArtKey, String>(same = { a, b -> a == b })
        val first = cache.get(key(FakeBitmap("x"))) { String(charArrayOf('x')) }
        val second = cache.get(key(FakeBitmap("x"))) { String(charArrayOf('x')) }
        assertSame(first, second)
    }

    @Test fun differentArt_isNotKeptAsSame() {
        val cache = SingleSlotCache<ArtKey, String>(same = { a, b -> a == b })
        val first = cache.get(key(FakeBitmap("x"))) { "x" }
        val second = cache.get(key(FakeBitmap("y"))) { "y" }
        assertNotSame(first, second)
        assertEquals("y", second)
    }

    @Test fun cachedNull_isStillAHit() {
        val cache = SingleSlotCache<ArtKey, String?>()
        var made = 0
        cache.get(key(null)) { made++; null }
        cache.get(key(null)) { made++; null }
        assertEquals(1, made)
    }

    // --- ArtSource: which source bitmap to show (Spotify's coverless updates, couch round 1 11-02) ---

    private val song = TrackKey("spotify", "Song", "Artist")

    @Test fun sameTrack_updateWithoutCover_keepsCover() {
        // Spotify on play/pause: several updates for the same title, some with no ART/ALBUM_ART but a DISPLAY_ICON.
        val pick = ArtSource<FakeBitmap>()
        val cover = FakeBitmap("cover")
        assertSame(cover, pick.choose(song, cover = cover, icon = FakeBitmap("icon1")))
        assertSame(cover, pick.choose(song, cover = null, icon = FakeBitmap("icon2")))
        assertSame(cover, pick.choose(song, cover = null, icon = null))
    }

    @Test fun sameTrack_newCoverBitmap_replaces() {
        // Couch test 2: the new track's text arrives with the OLD cover, then the new cover with the same text.
        val pick = ArtSource<FakeBitmap>()
        val oldCover = FakeBitmap("old")
        val newCover = FakeBitmap("new")
        assertSame(oldCover, pick.choose(song, cover = oldCover, icon = null))
        assertSame(newCover, pick.choose(song, cover = newCover, icon = null))
        // ...and a coverless update after that keeps the NEW one.
        assertSame(newCover, pick.choose(song, cover = null, icon = FakeBitmap("icon")))
    }

    @Test fun newTrack_withoutCover_usesIconFallback() {
        val pick = ArtSource<FakeBitmap>()
        pick.choose(song, cover = FakeBitmap("cover"), icon = null)
        val icon = FakeBitmap("icon")
        assertSame(icon, pick.choose(TrackKey("spotify", "Other song", "Artist"), cover = null, icon = icon))
        // Same title from another app is another track too.
        val icon2 = FakeBitmap("icon2")
        pick.choose(song, cover = FakeBitmap("cover"), icon = null)
        assertSame(icon2, pick.choose(TrackKey("youtube", "Song", "Artist"), cover = null, icon = icon2))
        // No cover and no icon → null (the caller falls back to the app icon).
        assertEquals(null, pick.choose(TrackKey("spotify", "Third", null), cover = null, icon = null))
    }

    @Test fun newTrack_withCover_usesIt() {
        val pick = ArtSource<FakeBitmap>()
        pick.choose(song, cover = FakeBitmap("cover"), icon = null)
        val next = FakeBitmap("next")
        assertSame(next, pick.choose(TrackKey("spotify", "Other song", "Artist"), cover = next, icon = FakeBitmap("icon")))
    }
}

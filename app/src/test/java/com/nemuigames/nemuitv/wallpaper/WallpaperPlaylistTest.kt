package com.nemuigames.nemuitv.wallpaper

import com.nemuigames.nemuitv.config.WallpaperOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class WallpaperPlaylistTest {
    @Test fun sequential_wraps() {
        val p = WallpaperPlaylist(listOf("a", "b", "c"), WallpaperOrder.SEQUENTIAL)
        assertEquals("a", p.current())
        assertEquals("b", p.advance())
        assertEquals("c", p.advance())
        assertEquals("a", p.advance())
    }

    @Test fun shuffle_noRepeatUntilAllPlayed() {
        val files = listOf("a", "b", "c", "d", "e")
        val p = WallpaperPlaylist(files, WallpaperOrder.SHUFFLE, Random(42))
        var last = p.current()!!
        val cycle = mutableListOf(last)
        repeat(20) { round ->
            while (cycle.size < files.size) cycle += p.advance()!!
            assertEquals("round $round covers every file once", files.toSet(), cycle.toSet())
            assertEquals(files.size, cycle.size)
            last = cycle.last()
            val first = p.advance()!!
            assertNotEquals("new cycle must not repeat the previous video", last, first)
            cycle.clear(); cycle += first
        }
    }

    @Test fun empty_currentIsNull() {
        val p = WallpaperPlaylist(emptyList(), WallpaperOrder.SHUFFLE)
        assertNull(p.current())
        assertNull(p.advance())
    }

    @Test fun markBroken_skips() {
        val p = WallpaperPlaylist(listOf("a", "b", "c"), WallpaperOrder.SEQUENTIAL)
        assertEquals("b", p.markBroken("a"))
        assertEquals("c", p.advance())
        assertEquals("b", p.advance()) // "a" is never played again
    }

    @Test fun allBroken_returnsNull() {
        val p = WallpaperPlaylist(listOf("a", "b"), WallpaperOrder.SEQUENTIAL)
        p.markBroken("a")
        assertNull(p.markBroken("b"))
        assertNull(p.current())
    }
}

class WallpaperPlaylistStartAtTest {
    @Test fun startAt_keepsCurrentVideo() {
        val seq = WallpaperPlaylist(listOf("a", "b", "c"), WallpaperOrder.SEQUENTIAL, startAt = "b")
        assertEquals("b", seq.current())
        assertEquals("c", seq.advance())
        val shuf = WallpaperPlaylist(listOf("a", "b", "c", "d"), WallpaperOrder.SHUFFLE, Random(1), startAt = "c")
        assertEquals("c", shuf.current())
        val rest = List(3) { shuf.advance() }
        assertEquals(setOf("a", "b", "d"), rest.toSet())
        assertEquals("a", WallpaperPlaylist(listOf("a", "b"), WallpaperOrder.SEQUENTIAL, startAt = "gone").current())
    }
}

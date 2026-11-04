package com.nemuigames.nemuitv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockInkTest {
    /** Rows of '#' (ink) and '.' (empty) → alpha grid. */
    private fun grid(vararg rows: String): Triple<IntArray, Int, Int> {
        val w = rows[0].length
        val a = IntArray(w * rows.size)
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') a[y * w + x] = 255 } }
        return Triple(a, w, rows.size)
    }

    @Test fun one_withFlag_returnsStemColumn() {
        // A "1": the top flag reaches column 1, the stem is at columns 4-5. Lower 60% = the bottom 6 of 10 rows.
        val (a, w, h) = grid(
            "..........",
            "....##....",
            "...###....",
            "..####....",
            ".##.##....",
            "....##....",
            "....##....",
            "....##....",
            "....##....",
            "....##....",
            "....##....",
            "..........",
        )
        assertEquals(4, leftmostLowerInk(a, w, h))
    }

    @Test fun plainGlyph_unchanged() {
        // An "0"-like ring: leftmost ink is the same everywhere.
        val (a, w, h) = grid(
            "..###..",
            ".#...#.",
            ".#...#.",
            ".#...#.",
            ".#...#.",
            "..###..",
        )
        assertEquals(1, leftmostLowerInk(a, w, h))
    }

    @Test fun faintPixelsBelowThreshold_ignored() {
        val w = 4; val h = 4
        val a = IntArray(w * h)
        for (y in 0 until h) { a[y * w + 2] = 255; a[y * w + 0] = 10 } // column 0 is only a faint fringe
        assertEquals(2, leftmostLowerInk(a, w, h, threshold = 32))
    }

    @Test fun empty_returnsNull() = assertNull(leftmostLowerInk(IntArray(9), 3, 3))
}

package com.nemuigames.nemuitv.home

/**
 * Optical left edge of a rendered glyph: the leftmost column with ink (alpha > [threshold]) in the LOWER
 * [lowerFraction] of the glyph's inked height. Ignores a "1"'s top flag, so the date lines up with its stem;
 * for other digits it's the usual leftmost ink. [alpha] is row-major, [width] × [height]. Null = no ink.
 */
fun leftmostLowerInk(alpha: IntArray, width: Int, height: Int, lowerFraction: Float = 0.6f, threshold: Int = 0): Int? {
    fun inked(x: Int, y: Int) = alpha[y * width + x] > threshold
    fun rowInked(y: Int) = (0 until width).any { inked(it, y) }
    val top = (0 until height).firstOrNull(::rowInked) ?: return null
    val bottom = (height - 1 downTo 0).first(::rowInked)
    val rows = kotlin.math.ceil((bottom - top + 1) * lowerFraction).toInt().coerceAtLeast(1)
    val from = bottom - rows + 1
    return (0 until width).firstOrNull { x -> (from..bottom).any { y -> inked(x, y) } }
}

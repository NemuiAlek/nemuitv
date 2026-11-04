package com.nemuigames.nemuitv.controlcenter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nemuigames.nemuitv.home.Jt

/**
 * The banner ⇄ player morph: one glass box grows out of the banner's top-right corner into the player.
 * Everything is a function of one progress `p` (0 = banner, 1 = player), so closing is the open played backwards:
 * the rows fade out bottom-first, the clear glass box shrinks to the banner's size, and only then does it cross-fade
 * into the banner. (The banner comes back focused, solid white: fading it in while the box was still bigger than it,
 * then dropping the box, read as a white flash — seen frame by frame on the emulator, 11-03.)
 */
object PlayerMorph {
    const val OPEN_MS = 300
    const val CLOSE_MS = 260
    /** The player's rows: song row, buttons, progress bar. */
    const val ROWS = 3

    /** The box ⇄ banner cross-fade, at banner size: the first 30 %. */
    private const val SWAP = 0.3f

    private fun window(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

    /** How far the glass box has grown from banner size (0) to player size (1). Eased; only after the swap, done at 80 %. */
    fun box(p: Float): Float = FastOutSlowInEasing.transform(window(p, SWAP, 0.8f))

    /** The banner: there at 0, gone by [SWAP]. */
    fun bannerAlpha(p: Float): Float = 1f - window(p, 0f, SWAP)

    /** The glass box: cross-fades with the banner (they add up to 1 during the swap). */
    fun boxAlpha(p: Float): Float = window(p, 0f, SWAP)

    /** Row [row] fades in over 20 %, starting at 55 % and 12 % after the row above it. */
    fun rowAlpha(row: Int, p: Float): Float = window(p, 0.55f + row * 0.12f, 0.75f + row * 0.12f)
}

/** The banner's size: the box starts here. */
private val MorphFromW = 150.dp
private val MorphFromH = 40.dp
private val MorphFromRadius = 16.dp
private val MorphToRadius = 14.dp

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The box at progress [t], in a [full]-sized (the player's) area: anchored top-right. */
private fun morphRect(full: Size, t: Float, density: Density): RoundRect = with(density) {
    val w = lerp(MorphFromW.toPx().coerceAtMost(full.width), full.width, t)
    val h = lerp(MorphFromH.toPx().coerceAtMost(full.height), full.height, t)
    val r = lerp(MorphFromRadius.toPx(), MorphToRadius.toPx(), t)
    RoundRect(full.width - w, 0f, full.width, h, CornerRadius(r))
}

private class MorphClip(private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(morphRect(size, t, density))
}

/**
 * Draws the growing glass box behind the player and clips the player to it. The player is laid out once at full
 * size; only drawing changes per frame (no relayout: cheap on the Streamer). [progress] is read in the draw phase.
 */
fun Modifier.playerMorph(progress: () -> Float): Modifier = this
    .drawBehind {
        val p = progress()
        val a = PlayerMorph.boxAlpha(p)
        if (a > 0f) {
            val path = Path().apply { addRoundRect(morphRect(size, PlayerMorph.box(p), this@drawBehind)) }
            drawPath(path, Jt.Frost, alpha = a)
            drawPath(path, Jt.FrostBorder, alpha = a, style = Stroke(1.dp.toPx()))
        }
    }
    .graphicsLayer {
        clip = true
        shape = MorphClip(PlayerMorph.box(progress()))
    }

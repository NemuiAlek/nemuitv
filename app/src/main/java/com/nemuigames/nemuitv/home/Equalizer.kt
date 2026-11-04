package com.nemuigames.nemuitv.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Bar heights (fraction of the icon's height) while paused: three short, still bars. */
private val PausedBars = floatArrayOf(0.3f, 0.45f, 0.3f)
/** Per bar: speed (whole cycles per loop, so the loop is seamless) and start offset → staggered, never in step. */
private val BarSpeed = intArrayOf(3, 4, 3)
private val BarOffset = floatArrayOf(0f, 0.3f, 0.55f)
private const val LOOP_MS = 2_400 // → the outer bars bounce every 800 ms, the middle one every 600 ms

/**
 * Small 3-bar equalizer: bars bounce while [playing], short and still while paused. Drawn on a Canvas (no images);
 * the one animated value is read only while drawing, so it redraws without recomposing. Nothing runs while the
 * host isn't visible (an app in front of Home): the animation only exists while [playing] and the lifecycle is STARTED.
 */
@Composable
fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (playing && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        val phase = rememberInfiniteTransition(label = "eq")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "eqPhase")
        Canvas(modifier) { bars(color) { i -> 0.25f + 0.75f * abs(sin(PI * (BarSpeed[i] * phase.value + BarOffset[i]))).toFloat() } }
    } else {
        Canvas(modifier) { bars(color) { i -> PausedBars[i] } }
    }
}

/** Three bottom-aligned rounded bars; [height] = fraction of the icon's height for bar 0..2. */
private inline fun DrawScope.bars(color: Color, height: (Int) -> Float) {
    val gap = size.width * 0.16f
    val w = (size.width - 2 * gap) / 3
    for (i in 0 until 3) {
        val h = size.height * height(i)
        drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
    }
}


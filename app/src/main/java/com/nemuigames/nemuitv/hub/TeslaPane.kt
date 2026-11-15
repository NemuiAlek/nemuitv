package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt

private val BarGreen = Color(0xFF3CCF6E)
private val LowOrange = Color(0xFFFF9F43)

/** Fill fraction of the battery bar: empty when the percent is unknown, clamped to 0..1. */
fun barFraction(percent: Int?): Float = ((percent ?: 0) / 100f).coerceIn(0f, 1f)

/**
 * The Tesla pane (spec §2.2): one read-only card per car, stats on the left, top-down car with tire psi on the right.
 * Nothing here is focusable.
 */
@Composable
fun TeslaPane(cards: List<CarStats>, inert: Boolean) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No cars in Home Assistant", fontSize = 15.sp, color = Jt.TextSecondary)
        }
        return
    }
    Row(
        Modifier.fillMaxSize().graphicsLayer { alpha = if (inert) 0.45f else 1f },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cards.forEach { c -> CarCard(c, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun CarCard(c: CarStats, modifier: Modifier) {
    Column(modifier.then(Glass.matte(RoundedCornerShape(14.dp))).padding(16.dp)) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CarGlyph(c.color)
                    Text(c.name, fontSize = 16.sp, color = Jt.TextPrimary, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Text(c.percentText, fontSize = 34.sp, color = Jt.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(barFraction(c.percent)).fillMaxHeight().background(BarGreen))
                }
                Spacer(Modifier.height(8.dp))
                // "💤 Asleep · last update Nov 13, 6:24 AM" doesn't fit one line here: the time goes on its own line.
                val status = c.statusText.split(" · ", limit = 2)
                Text(status[0], fontSize = 12.sp, color = Jt.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status.getOrNull(1)?.let {
                    Text(it, fontSize = 12.sp, color = Jt.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                StatLine("Range", c.rangeText)
                StatLine("Odometer", c.odometerText)
            }
            TopDownCar(c.tires, Modifier.weight(0.62f).fillMaxHeight())
        }
        Spacer(Modifier.height(8.dp))
        Text("${c.insideText}   ${c.outsideText}", fontSize = 13.sp, color = Jt.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Jt.TextSecondary)) { append("$label ") }
            withStyle(SpanStyle(color = Jt.TextPrimary)) { append(value) }
        },
        fontSize = 12.sp, maxLines = 1,
    )
}

/** Top-down car on the mockup's 120×170 viewBox, nose up; each psi sits outside its wheel. */
@Composable
private fun TopDownCar(tires: Map<String, Tire>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val k = minOf(size.width / 120f, size.height / 170f)
        val ox = (size.width - 120f * k) / 2f
        val oy = (size.height - 170f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
        fun sz(w: Float, h: Float) = Size(w * k, h * k)
        val white = Color.White
        drawRoundRect(white.copy(alpha = 0.08f), p(34f, 18f), sz(52f, 134f), CornerRadius(24f * k))
        drawRoundRect(white.copy(alpha = 0.75f), p(34f, 18f), sz(52f, 134f), CornerRadius(24f * k), style = Stroke(2.5f * k))
        drawRoundRect(white.copy(alpha = 0.18f), p(41f, 50f), sz(38f, 26f), CornerRadius(7f * k))
        drawRoundRect(white.copy(alpha = 0.18f), p(41f, 100f), sz(38f, 22f), CornerRadius(7f * k))
        // position -> (wheel x, wheel y, text on the left of the wheel?)
        val wheels = listOf(
            Triple("front_left", 27f to 36f, true), Triple("front_right", 84f to 36f, false),
            Triple("rear_left", 27f to 110f, true), Triple("rear_right", 84f to 110f, false),
        )
        val fontPx = 15f * k
        wheels.forEach { (pos, xy, left) ->
            val t = tires[pos] ?: Tire("—", false)
            val color = if (t.low) LowOrange else white
            drawRoundRect(color, p(xy.first, xy.second), sz(9f, 24f), CornerRadius(3f * k))
            val layout = measurer.measure(t.text, TextStyle(color = color, fontSize = (fontPx / density).sp))
            val cy = oy + (xy.second + 17f) * k - layout.size.height / 2f
            val x = if (left) ox + 22f * k - layout.size.width else ox + 98f * k
            drawText(layout, topLeft = Offset(x, cy))
        }
        val unit = measurer.measure("psi", TextStyle(color = white.copy(alpha = 0.7f), fontSize = (11f * k / density).sp))
        drawText(unit, topLeft = Offset(ox + 60f * k - unit.size.width / 2f, oy + 168f * k - unit.size.height))
    }
}

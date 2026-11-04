package com.nemuigames.nemuitv.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Mac-style Control Center glyph: two toggle pills. */
@Composable
fun ControlCenterGlyph(color: Color, size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val pillH = h * 0.34f; val r = CornerRadius(pillH / 2, pillH / 2); val stroke = Stroke(width = w * 0.075f)
        // top pill, knob left
        drawRoundRect(color, Offset(0f, h * 0.10f), Size(w, pillH), r, style = stroke)
        drawCircle(color, pillH * 0.30f, Offset(pillH / 2, h * 0.10f + pillH / 2))
        // bottom pill, knob right
        drawRoundRect(color, Offset(0f, h * 0.56f), Size(w, pillH), r, style = stroke)
        drawCircle(color, pillH * 0.30f, Offset(w - pillH / 2, h * 0.56f + pillH / 2))
    }
}

/** Simple house outline for the ⌂ Home tile. */
@Composable
fun HouseGlyph(color: Color, size: Dp = 44.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.12f, h * 0.48f); lineTo(w * 0.5f, h * 0.14f); lineTo(w * 0.88f, h * 0.48f)
            moveTo(w * 0.22f, h * 0.40f); lineTo(w * 0.22f, h * 0.86f); lineTo(w * 0.78f, h * 0.86f); lineTo(w * 0.78f, h * 0.40f)
            moveTo(w * 0.42f, h * 0.86f); lineTo(w * 0.42f, h * 0.62f); lineTo(w * 0.58f, h * 0.62f); lineTo(w * 0.58f, h * 0.86f)
        }
        drawPath(p, color, style = Stroke(width = w * 0.07f, join = StrokeJoin.Round))
    }
}

/** Eye with a slash through it, for the Hidden apps tile. */
@Composable
fun HiddenGlyph(color: Color, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val stroke = Stroke(width = w * 0.07f, join = StrokeJoin.Round)
        val eye = Path().apply {
            moveTo(w * 0.06f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.08f, w * 0.94f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.92f, w * 0.06f, h * 0.5f)
        }
        drawPath(eye, color, style = stroke)
        drawCircle(color, w * 0.12f, Offset(w * 0.5f, h * 0.5f), style = stroke)
        drawLine(color, Offset(w * 0.16f, h * 0.84f), Offset(w * 0.84f, h * 0.16f), strokeWidth = w * 0.07f)
    }
}

/** HA weather condition → emoji (renders in colour on Google TV). */
fun weatherEmoji(condition: String): String = when (condition) {
    "sunny" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "rainy" -> "🌧️"
    "pouring" -> "🌧️"
    "lightning", "lightning-rainy" -> "⛈️"
    "snowy", "snowy-rainy" -> "❄️"
    "hail" -> "🌨️"
    "fog" -> "🌫️"
    "windy", "windy-variant" -> "💨"
    "exceptional" -> "⚠️"
    else -> "☁️"
}

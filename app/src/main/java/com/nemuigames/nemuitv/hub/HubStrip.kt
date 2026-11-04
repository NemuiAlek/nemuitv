package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt

/** Warm yellow for "on" (lights on, lit tiles), from the locked mockups. */
val HubWarm = Color(0xFFFFD76A)

private val StripShape = RoundedCornerShape(8.dp)

/**
 * The status strip above the pane: information only (not focusable). Items spaced evenly across its full width, which
 * the caller sets to exactly the pane's width (locked layout: "perfect, don't move it").
 * [dimmed]: HA isn't confirming these values right now (offline / not live yet): the whole row is greyed.
 */
@Composable
fun HubStrip(strip: Strip?, dimmed: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.graphicsLayer { alpha = if (dimmed) 0.45f else 1f }.then(Glass.surface(StripShape)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        strip?.let { stripItems(it) }.orEmpty().forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.carColor?.let { CarGlyph(it); Spacer(Modifier.width(5.dp)) }
                Text(
                    item.text, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    color = if (item.warm) HubWarm else Jt.TextPrimary,
                )
            }
        }
    }
}

// The mockup's side-view sports car (SVG viewBox "2 3.5 60.5 18"): sleek fastback body, two windows, two wheels.
private const val CarVbX = 2f
private const val CarVbY = 3.5f
private const val CarVbW = 60.5f
private const val CarVbH = 18f

private val carBody = Path().apply {
    moveTo(3f, 15.5f); cubicTo(3f, 12.5f, 5f, 11.5f, 9f, 11f); lineTo(19f, 9.6f)
    cubicTo(24f, 5.5f, 30f, 4.2f, 37f, 4.4f); cubicTo(44f, 4.6f, 50f, 7.5f, 55f, 10.4f)
    cubicTo(59f, 11f, 61.5f, 12.2f, 61.5f, 14.6f); lineTo(61.5f, 16.2f)
    cubicTo(61.5f, 17f, 60.8f, 17.5f, 60f, 17.5f); lineTo(4.5f, 17.5f)
    cubicTo(3.6f, 17.5f, 3f, 16.8f, 3f, 15.5f); close()
}
private val carWindows = Path().apply {
    moveTo(21.5f, 10f); cubicTo(26f, 6.7f, 31f, 5.6f, 36.5f, 5.8f); lineTo(37.5f, 10.2f); close()
    moveTo(39.5f, 5.9f); cubicTo(44f, 6.3f, 48f, 8f, 51.5f, 10.3f); lineTo(40.5f, 10.3f); close()
}

/** Car body colors from config (`hub.carColors`): white, gray (the default) or darkgray. */
private fun carFill(color: String) = when (color) {
    "darkgray" -> Color(0xFF3F444C)
    "white" -> Color(0xFFF4F5F7)
    else -> Color(0xFF9AA0A8)
}
private fun carOutline(color: String) = Color.White.copy(alpha = if (color == "darkgray") 0.55f else 0.9f)

@Composable
fun CarGlyph(color: String, height: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(Modifier.size(height * (CarVbW / CarVbH), height)) {
        val k = size.height / CarVbH
        scale(k, k, pivot = Offset.Zero) {
            translate(-CarVbX, -CarVbY) {
                drawPath(carBody, carFill(color))
                drawPath(carBody, carOutline(color), style = Stroke(width = 1f))
                drawPath(carWindows, Color(0xFF14181E).copy(alpha = 0.75f))
                listOf(15f, 50f).forEach { x ->
                    drawCircle(Color(0xFF15181D), radius = 3.6f, center = Offset(x, 17.5f))
                    drawCircle(Color(0xFF9AA0A8), radius = 3.6f, center = Offset(x, 17.5f), style = Stroke(width = 1f))
                }
            }
        }
    }
}

package com.nemuigames.nemuitv.controlcenter

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.config.WifiConfig
import com.nemuigames.nemuitv.home.Jt
import kotlin.math.roundToInt

private val QrSize = 280.dp
private val QrBackground = Color(0xFF0B0F17)
private const val OPEN_MS = 380
private const val CLOSE_MS = 280

/** The arrows go nowhere while the QR is up (the panel underneath would otherwise take focus). */
private val ArrowKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)

/**
 * Full-screen guest Wi-Fi code: a guest points their phone camera at it to join.
 *
 * It grows out of the Wi-Fi circle ([origin], in this composable's coordinates; null → from the centre) into the full
 * screen, and when [closing] turns true it shrinks back into the circle, then calls [onClosed]. The caller handles Back
 * (it sets [closing]); this screen only takes focus and swallows the arrows.
 */
@Composable
fun WifiQrScreen(wifi: WifiConfig, origin: Rect?, closing: Boolean, onClosed: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val text = remember(wifi) { wifiQrText(wifi) }
    val px = with(LocalDensity.current) { QrSize.roundToPx() }
    val qr = remember(text, px) { text?.let { qrBitmap(it, px) } }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) {
            progress.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            onClosed()
        } else {
            progress.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing))
        }
    }

    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { it.key in ArrowKeys },
    ) {
        // The growing shape: circle-sized glass at the Wi-Fi button → the full dark screen. Reads the progress only in
        // the layout / draw phases, so each frame just re-lays-out this one box.
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val v = progress.value
                    val full = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    val from = origin ?: Rect(full.center, 0f)
                    val l = mix(from.left, full.left, v); val t = mix(from.top, full.top, v)
                    val w = mix(from.width, full.width, v).roundToInt().coerceAtLeast(0)
                    val h = mix(from.height, full.height, v).roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(l.roundToInt(), t.roundToInt()) }
                }
                .graphicsLayer {
                    val v = progress.value
                    // Round (half the circle's size) at the start, square corners at full screen.
                    shape = RoundedCornerShape(mix((origin?.minDimension ?: 0f) / 2f, 0f, v))
                    clip = true
                }
                .drawBehind { drawRect(lerp(Color.White.copy(alpha = 0.14f), QrBackground, progress.value)) },
        )
        // The code itself fades and settles in once the shape has mostly filled the screen (and out first on close).
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val v = progress.value
                alpha = ((v - 0.45f) / 0.55f).coerceIn(0f, 1f)
                val s = mix(0.85f, 1f, v)
                scaleX = s; scaleY = s
            },
            contentAlignment = Alignment.Center,
        ) {
            if (qr == null) {
                Text("Wi-Fi not set up", color = Jt.TextSecondary, fontSize = 24.sp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        bitmap = qr,
                        contentDescription = "Wi-Fi QR code for ${wifi.ssid}",
                        modifier = Modifier.size(QrSize).clip(RoundedCornerShape(12.dp)).background(Color.White),
                    )
                    Text(wifi.ssid, color = Jt.TextPrimary, fontSize = 26.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("Point your phone's camera here to join", color = Jt.TextSecondary, fontSize = 16.sp)
                }
            }
        }
    }
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

package com.nemuigames.nemuitv.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.ha.WeatherSnapshot
import com.nemuigames.nemuitv.settings.ClockMode
import com.nemuigames.nemuitv.settings.TempUnit
import com.nemuigames.nemuitv.settings.clockPattern
import com.nemuigames.nemuitv.settings.formatTemp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Big clock with weather beside it, small date underneath. [clock] and [unit] come from the Launcher settings. */
@Composable
fun ClockWeather(
    weather: WeatherSnapshot?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    clock: ClockMode = ClockMode.SYSTEM,
    unit: TempUnit = TempUnit.NONE,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Android's own minute tick (plus time/zone changes) keeps the clock right; a coroutine delay would pause
    // while the box sleeps and show the wrong time for up to a minute after the TV wakes.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { now = System.currentTimeMillis() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // …and refresh the moment Home is shown again.
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val timePattern = clockPattern(clock, system24h = DateFormat.is24HourFormat(context))
    val time = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(now))
    val date = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(now))

    // Big text starts with more empty space before its first digit than small text does, so the date looked
    // shifted left of the clock. Line the date's first ink up with the clock's first glyph's OPTICAL edge: its
    // leftmost ink in the lower 60% (a "1"'s top flag sticks out left of its stem and made the date look too far left).
    val density = LocalDensity.current
    val clockSizePx = with(density) { ClockSize.toPx() }
    val firstChar = time.firstOrNull()
    val clockGlyphEdge = remember(firstChar, clockSizePx) { firstChar?.let { opticalLeftEdge(it, clockSizePx, ClockWeight) } ?: 0f }
    var clockBoxLeft by remember { mutableFloatStateOf(0f) }
    var dateInk by remember { mutableFloatStateOf(0f) }
    val dateShift = with(density) { (clockBoxLeft + clockGlyphEdge - dateInk).coerceAtLeast(0f).toDp() }

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time, style = TextStyle(fontSize = ClockSize, fontWeight = ClockWeight, color = Jt.TextPrimary, shadow = Jt.Legible),
                onTextLayout = { r -> if (r.layoutInput.text.isNotEmpty()) clockBoxLeft = r.getBoundingBox(0).left })
            if (weather != null) {
                Spacer(Modifier.width(16.dp))
                Text(
                    "${weatherEmoji(weather.condition)} ${formatTemp(weather.temperature, weather.unit, unit)}",
                    modifier = Modifier.alpha(if (stale) 0.5f else 1f).padding(bottom = 8.dp),
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, color = Jt.TextPrimary, shadow = Jt.Legible),
                )
            }
        }
        Text(date, modifier = Modifier.padding(start = dateShift),
            style = TextStyle(fontSize = 14.sp, color = Jt.TextSecondary, shadow = Jt.Legible),
            onTextLayout = { r -> if (r.layoutInput.text.isNotEmpty()) dateInk = r.getBoundingBox(0).left + firstGlyphInset(r) })
    }
}


private val ClockSize = 52.sp
private val ClockWeight = FontWeight.Light

/**
 * Where [c]'s optical left edge is drawn, in px from its origin, at [sizePx] / [weight] (same font as the clock):
 * renders the glyph once into a small bitmap and scans it with [leftmostLowerInk].
 */
private fun opticalLeftEdge(c: Char, sizePx: Float, weight: FontWeight): Float {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight.weight, false)
    }
    val text = c.toString()
    val b = android.graphics.Rect().also { paint.getTextBounds(text, 0, 1, it) }
    if (b.isEmpty) return 0f
    val pad = 2
    val w = b.width() + 2 * pad
    val h = b.height() + 2 * pad
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    try {
        android.graphics.Canvas(bmp).drawText(text, (pad - b.left).toFloat(), (pad - b.top).toFloat(), paint)
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val alpha = IntArray(px.size) { px[it] ushr 24 }
        val col = leftmostLowerInk(alpha, w, h) ?: return b.left.toFloat()
        return (col - pad + b.left).toFloat()
    } finally {
        bmp.recycle()
    }
}

/**
 * Empty space between where a text's first character box starts and where its ink starts (the font's left side
 * bearing), measured with Android's Paint because Compose's bounding box includes that space.
 */
private fun firstGlyphInset(r: androidx.compose.ui.text.TextLayoutResult): Float {
    val text = r.layoutInput.text.text
    val style = r.layoutInput.style
    val paint = android.graphics.Paint().apply {
        textSize = with(r.layoutInput.density) { style.fontSize.toPx() }
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
            (style.fontWeight ?: FontWeight.Normal).weight, false)
    }
    val bounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, 1, bounds)
    return bounds.left.toFloat()
}

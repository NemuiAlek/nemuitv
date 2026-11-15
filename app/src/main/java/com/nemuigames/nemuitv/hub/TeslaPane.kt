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

/** A car's two controls on the Tesla pane. */
enum class CarControl { FAN, COP }

/** Where → from the Tesla rail item lands: the first car's fan, else its COP row, else the next car's; null = none. */
fun teslaFirstControl(cards: List<CarStats>): Pair<String, CarControl>? = cards.firstNotNullOfOrNull { c ->
    when {
        c.climate != null -> c.prefix to CarControl.FAN
        c.copText != null -> c.prefix to CarControl.COP
        else -> null
    }
}

/** "Turning on…" / "Turning off…" while a press waits for HA; the shown state is still the old one. */
fun turningText(wasOn: Boolean): String = if (wasOn) "Turning off…" else "Turning on…"

/** Per car, what the controls look like right now (from [HubUi.pending] and [HubUi.blockedCars]). */
data class CarControlsUi(val fanPending: Boolean, val copPending: Boolean, val blocked: Boolean)

/**
 * The Tesla pane (spec §2.2): one card per car, stats on the left, top-down car with tire psi on the right; a fan
 * button next to the inside temperature and the overheat-protection row are the only focusable things. Nothing here
 * takes focus by itself: the hub moves focus in with [firstFocus] (→ from the rail). ◀ on the first card → [onLeft].
 */
@Composable
fun TeslaPane(
    cards: List<CarStats>,
    inert: Boolean,
    controls: (String) -> CarControlsUi = { CarControlsUi(false, false, false) },
    onFan: (String) -> Unit = {},
    onCop: (String) -> Unit = {},
    onLeft: () -> Unit = {},
    firstFocus: FocusRequester = remember { FocusRequester() },
) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No cars in Home Assistant", fontSize = 15.sp, color = Jt.TextSecondary)
        }
        return
    }
    val first = teslaFirstControl(cards)
    Row(
        Modifier.fillMaxSize().graphicsLayer { alpha = if (inert) 0.45f else 1f },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ◀ goes to the rail from the leftmost card that has controls (the one → lands on), not only card 0.
        val leftIndex = first?.let { f -> cards.indexOfFirst { it.prefix == f.first } } ?: -1
        cards.forEachIndexed { i, c ->
            CarCard(
                c, controls(c.prefix), inert,
                onFan = { onFan(c.prefix) }, onCop = { onCop(c.prefix) },
                onLeftEdge = if (i == leftIndex) onLeft else null,
                firstFocus = firstFocus.takeIf { first?.first == c.prefix }, firstControl = first?.second,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CarCard(
    c: CarStats, ctl: CarControlsUi, inert: Boolean,
    onFan: () -> Unit, onCop: () -> Unit, onLeftEdge: (() -> Unit)?,
    firstFocus: FocusRequester?, firstControl: CarControl?,
    modifier: Modifier,
) {
    fun focusFor(control: CarControl) =
        if (firstFocus != null && firstControl == control) Modifier.focusRequester(firstFocus) else Modifier
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.insideText, fontSize = 13.sp, color = Jt.TextPrimary, maxLines = 1)
            c.climate?.let { look ->
                Spacer(Modifier.width(8.dp))
                FanButton(
                    look = look, pending = ctl.fanPending, blocked = ctl.blocked,
                    onOk = { if (!inert && !ctl.fanPending) onFan() }, onLeftEdge = onLeftEdge,
                    modifier = focusFor(CarControl.FAN),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(c.outsideText, fontSize = 13.sp, color = Jt.TextSecondary, maxLines = 1)
        }
        // Always takes its line, so the card doesn't jump when a press starts / ends.
        Text(
            if (ctl.fanPending && c.climate != null) turningText(wasOn = c.climate == ClimateLook.ON) else "",
            fontSize = 10.sp, color = Jt.TextSecondary, maxLines = 1,
        )
        c.copText?.let { cop ->
            Spacer(Modifier.height(6.dp))
            CopRow(
                state = cop, pending = ctl.copPending, blocked = ctl.blocked,
                onOk = { if (!inert && !ctl.copPending) onCop() }, onLeftEdge = onLeftEdge,
                modifier = Modifier.fillMaxWidth().then(focusFor(CarControl.COP)),
            )
        }
    }
}

private val IceBlue = Color(0xFF8FD8FF)
/** How much an unknown-state fan is dimmed (outline + glyph). */
private const val UNKNOWN_ALPHA = 0.45f
private val FanShape = RoundedCornerShape(8.dp)
private val CopShape = RoundedCornerShape(8.dp)
/** Blocked (controls not connected): gray and see-through, but still focusable (and visibly so) so OK can explain why. */
private fun blockedAlpha(blocked: Boolean, focused: Boolean) = if (!blocked) 1f else if (focused) 0.6f else 0.35f

/** OK once per press (on release), ◀ → [onLeftEdge] when set; the same handling as the Overview buttons. */
@Composable
private fun Modifier.carControlKeys(onOk: () -> Unit, onLeftEdge: (() -> Unit)?, onFocus: (Boolean) -> Unit): Modifier {
    val press = remember { OkPress() }
    DisposableEffect(Unit) { onDispose { press.reset() } }
    val ok by rememberUpdatedState(onOk)
    val leftEdge by rememberUpdatedState(onLeftEdge)
    return this
        .onFocusChanged { onFocus(it.isFocused); if (!it.isFocused) press.reset() }
        .onPreviewKeyEvent { e ->
            when {
                e.key == Key.DirectionLeft && leftEdge != null -> { if (e.type == KeyEventType.KeyDown) leftEdge?.invoke(); true }
                isOk(e.key) && e.type == KeyEventType.KeyDown -> { press.onDown(e.nativeKeyEvent.repeatCount, false); true }
                isOk(e.key) && e.type == KeyEventType.KeyUp -> { if (press.onUp() == OkPress.Action.CLICK) ok(); true }
                else -> false
            }
        }
}

@Composable
private fun pendingPulse(pending: Boolean): Float = if (pending) {
    val tr = rememberInfiniteTransition(label = "pending")
    tr.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
} else 1f

/**
 * The fan [❄]: outline when off, filled ice-blue when on, a dimmed outline (no fill) when the state is unknown,
 * dimmed and pulsing while pending, gray when blocked. Unknown still takes OK (it sends turn_on).
 */
@Composable
private fun FanButton(
    look: ClimateLook, pending: Boolean, blocked: Boolean,
    onOk: () -> Unit, onLeftEdge: (() -> Unit)?, modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // Dimming goes into the colors, not a graphicsLayer on the Surface: a layer alpha there also hid the focus
    // outline and turned the focus glow into a gray square. The focus outline itself always stays full white.
    val on = look == ClimateLook.ON
    val a = pendingPulse(pending) * blockedAlpha(blocked, focused) * (if (look == ClimateLook.UNKNOWN) UNKNOWN_ALPHA else 1f)
    val fill = when {
        blocked -> Color.White.copy(alpha = if (focused) 0.35f else 0.12f)
        on -> IceBlue
        focused -> Color.White.copy(alpha = 0.22f)
        else -> Color.Transparent
    }.let { it.copy(alpha = it.alpha * a) }
    val glyph = (if (on && !blocked) Jt.TextOnFocus else Color.White).let { it.copy(alpha = it.alpha * a) }
    Surface(
        onClick = {}, // OK is handled in carControlKeys (once per press)
        modifier = modifier.carControlKeys(onOk, onLeftEdge) { focused = it },
        shape = ClickableSurfaceDefaults.shape(FanShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = fill, focusedContainerColor = fill, pressedContainerColor = fill,
            contentColor = glyph, focusedContentColor = glyph, pressedContentColor = glyph,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = if (on) 0f else 0.7f * a)), shape = FanShape),
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = FanShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Snowflake(glyph, Modifier.padding(horizontal = 9.dp, vertical = 5.dp).size(16.dp))
    }
}

/** A six-armed snowflake (drawn, so it takes the button's colors; the ❄ emoji would ignore them). */
@Composable
private fun Snowflake(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val w = r * 0.16f
        repeat(6) { i ->
            val a = Math.toRadians(90.0 + i * 60.0)
            fun pt(len: Float, da: Double = 0.0) =
                Offset(c.x + (len * cos(a + da)).toFloat(), c.y - (len * sin(a + da)).toFloat())
            drawLine(color, c, pt(r), w, StrokeCap.Round)
            val mid = pt(r * 0.58f)
            drawLine(color, mid, Offset(mid.x + (r * 0.3f * cos(a + 0.8)).toFloat(), mid.y - (r * 0.3f * sin(a + 0.8)).toFloat()), w, StrokeCap.Round)
            drawLine(color, mid, Offset(mid.x + (r * 0.3f * cos(a - 0.8)).toFloat(), mid.y - (r * 0.3f * sin(a - 0.8)).toFloat()), w, StrokeCap.Round)
        }
    }
}

/** "Overheat protection   On": the whole row is the control (glass like the Overview buttons). */
@Composable
private fun CopRow(
    state: String, pending: Boolean, blocked: Boolean,
    onOk: () -> Unit, onLeftEdge: (() -> Unit)?, modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val alpha = pendingPulse(pending) * blockedAlpha(blocked, focused)
    Surface(
        onClick = {},
        modifier = modifier.carControlKeys(onOk, onLeftEdge) { focused = it },
        shape = ClickableSurfaceDefaults.shape(CopShape),
        colors = Glass.colors(), border = Glass.border(CopShape), glow = Glass.glow(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Dimmed content only (see FanButton): the row's glass and focus look stay as they are.
        Row(
            Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Overheat protection", fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            val shown = if (pending) turningText(wasOn = state == "On" || state == "No A/C") else state
            val warm = state == "On" && !pending && !blocked
            Text(
                shown, fontSize = 13.sp, maxLines = 1,
                color = if (warm) (if (focused) WarmOnWhite else HubWarm) else Color.Unspecified,
            )
        }
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

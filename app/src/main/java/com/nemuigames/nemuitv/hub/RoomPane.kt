package com.nemuigames.nemuitv.hub

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TileShape = RoundedCornerShape(8.dp)
private val TileH = 66.dp
private val TileGap = 6.dp

internal fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * A room: header `Living Room · 3 on` with Room off on the right, then its device tiles 3 per row.
 * OK on a tile toggles it; hold OK on an available dimmable light opens the brightness sheet ([onHold]);
 * ◀ from the left column (or from Room off) goes back to the rail ([onLeft]).
 * [offline]: everything greyed and inert (still focusable, so focus never falls off the page). The hub passes true
 * whenever HA isn't confirming the shown states (offline, or not live yet).
 * [canRoomOff]: false = Room off greyed and inert (nothing it would turn off is on).
 */
@Composable
fun RoomPane(
    room: Room,
    tiles: List<TileUi>,
    onCount: Int,
    offline: Boolean,
    canRoomOff: Boolean,
    onToggle: (String) -> Unit,
    onRoomOff: () -> Unit,
    onHold: (TileUi) -> Unit,
    onLeft: () -> Unit,
    tileFocus: (String) -> FocusRequester,
    roomOffFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val left by rememberUpdatedState(onLeft)
    val roomOff by rememberUpdatedState(onRoomOff)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(room.name) }
                    // Parked (inert): the count may be old, so it dims like the tiles.
                    withStyle(SpanStyle(color = HubWarm.copy(alpha = if (offline) 0.45f else 1f), fontSize = 14.sp)) {
                        append(roomOnSuffix(onCount))
                    }
                },
                fontSize = 17.sp, color = Jt.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val roomOffLive = canRoomOff && !offline
            Surface(
                onClick = { if (roomOffLive) roomOff() },
                modifier = Modifier.focusRequester(roomOffFocus).graphicsLayer { alpha = if (roomOffLive) 1f else 0.45f }
                    .onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionLeft) { if (e.type == KeyEventType.KeyDown) left(); true } else false
                    },
                shape = ClickableSurfaceDefaults.shape(TileShape),
                colors = Glass.colors(), border = Glass.border(TileShape), glow = Glass.glow(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text("⬛ Room off", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            contentPadding = PaddingValues(vertical = 2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(tiles, key = { _, t -> t.tile.entityId }) { i, t ->
                DeviceTile(
                    t, offline,
                    onClick = { if (!offline && !t.unavailable) onToggle(t.tile.entityId) },
                    onHold = { if (!offline && !t.unavailable && t.dimmable) onHold(t) },
                    onLeftEdge = if (i % 3 == 0) onLeft else null,
                    modifier = Modifier.focusRequester(tileFocus(t.tile.entityId)),
                )
            }
        }
    }
}

@Composable
private fun DeviceTile(
    t: TileUi,
    offline: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    onLeftEdge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val lit = t.on && !t.unavailable
    val press = remember { OkPress() }
    val scope = rememberCoroutineScope()
    val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val timer = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { timer[0]?.cancel(); press.reset() } }
    // The key handler and hold timer call the latest callbacks (the tile's state may change while OK is held).
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    val leftEdge by rememberUpdatedState(onLeftEdge)
    // Only a tile that can open the brightness sheet has a "hold": on any other tile a long press is just a press
    // (toggles on release; unavailable / offline tiles still send nothing).
    val canHold by rememberUpdatedState(t.dimmable && !t.unavailable && !offline)
    fun act(a: OkPress.Action) = when (a) {
        OkPress.Action.CLICK -> click()
        OkPress.Action.LONG -> { timer[0]?.cancel(); hold() }
        OkPress.Action.NONE -> Unit
    }

    val pulse = if (t.pending) {
        val tr = rememberInfiniteTransition(label = "pending")
        tr.animateFloat(0.6f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    val dim = if (t.unavailable || offline) 0.45f else 1f

    Surface(
        onClick = {}, // keys are handled below (click vs. hold); this is for focus + the glass look
        modifier = modifier.fillMaxWidth().height(TileH)
            .graphicsLayer { alpha = pulse * dim }
            .onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                when {
                    e.key == Key.DirectionLeft && leftEdge != null -> { if (e.type == KeyEventType.KeyDown) leftEdge?.invoke(); true }
                    isOk(e.key) && e.type == KeyEventType.KeyDown -> {
                        val a = press.onDown(native.repeatCount, native.isLongPress && canHold)
                        if (native.repeatCount == 0) timer[0]?.cancel()
                        if (native.repeatCount == 0 && canHold) {
                            timer[0] = scope.launch { delay(holdMs); act(press.fire()) }
                        }
                        act(a); true
                    }
                    isOk(e.key) && e.type == KeyEventType.KeyUp -> { timer[0]?.cancel(); act(press.onUp()); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = if (lit) ClickableSurfaceDefaults.colors(
            containerColor = HubWarm.copy(alpha = 0.22f), focusedContainerColor = Color.White,
            pressedContainerColor = Color.White,
            contentColor = Jt.TextPrimary, focusedContentColor = Jt.TextOnFocus, pressedContentColor = Jt.TextOnFocus,
        ) else Glass.colors(),
        border = if (lit) ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, HubWarm.copy(alpha = 0.55f)), shape = TileShape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = TileShape),
        ) else Glass.border(TileShape),
        glow = Glass.glow(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(tileIcon(t.tile.domain), fontSize = 13.sp, lineHeight = 15.sp)
            Column {
                Text(t.tile.name, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tileStateLine(t), fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
                if (lit && t.dimmable && t.brightness != null) {
                    Spacer(Modifier.height(3.dp))
                    BrightnessBar(t.brightness)
                }
                val cycleDone = t.cycle?.fraction
                if (!t.unavailable && cycleDone != null) {
                    Spacer(Modifier.height(3.dp))
                    BrightnessBar((cycleDone * 100).toInt())
                }
            }
        }
    }
}

@Composable
internal fun BrightnessBar(percent: Int) {
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.18f), shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(HubWarm, shape))
    }
}

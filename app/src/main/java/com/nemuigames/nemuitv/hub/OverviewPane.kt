package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.home.Jt
import androidx.compose.runtime.key
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.camera.CameraBank
import com.nemuigames.nemuitv.home.Glass

private val ButtonShape = RoundedCornerShape(8.dp)
/** Upper bound for the camera row; the 636 dp TV pane gives ≈ 144 dp (M3c's 16:9 placeholders were ≈ 116). */
private val MaxCameraRow = 150.dp

/**
 * The Overview pane (spec §2.1, mockup overview-thermostat.html): the live camera row ([CameraRow], not focusable), the scene
 * buttons, then the thermostat tile (≈ 60 %) and the lock tile. Focus moves as a grid; ◀ from the left column →
 * [onLeft]. OK fires once per press (on release, through [OkPress]); a held OK never repeats.
 * [inert]: everything dimmed to 0.45 and OK does nothing (still focusable, so focus never falls off the page).
 * [firstFocus] = the first scene (the thermostat tile when there are no scenes); [thermostatFocus] lets the hub put
 * focus back on the thermostat tile when its sheet closes.
 */
@Composable
fun OverviewPane(
    ui: HubUi,
    bank: CameraBank,
    scenes: List<SceneUi>,
    inert: Boolean,
    onScene: (String) -> Unit,
    onThermostat: () -> Unit,
    onLock: (String) -> Unit,
    onLeft: () -> Unit,
    firstFocus: FocusRequester,
    thermostatFocus: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
    /** Running appliance cycles (washer, dryer…): one row each, shown only while running. Not focusable. */
    cycles: List<Pair<Cycle, CycleProgress>> = emptyList(),
) {
    val model = ui.model
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Live cameras (each tile collects its own frames/state, so they never recompose this pane).
        val cameras = model?.cameras.orEmpty()
        if (cameras.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val h = cameraRowHeight(maxWidth.value, cameras.size, CameraGap.value, MaxCameraRow.value).dp
                CameraRow(bank, cameras, cameras.associateWith { entityName(it, ui.states[it]) }, h)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scenes.forEachIndexed { i, s ->
                OverviewButton(
                    pending = s.pending, dimmed = inert,
                    onOk = { if (!inert && !s.pending) onScene(s.entityId) },
                    onLeftEdge = if (i == 0) onLeft else null,
                    modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                ) { focused ->
                    Text(
                        buildAnnotatedString {
                            append("${s.icon} ${s.name}")
                            if (s.flagOn) {
                                append(" · ")
                                withStyle(SpanStyle(color = if (focused) WarmOnWhite else HubWarm)) { append("On") }
                            }
                        },
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val thermoId = model?.thermostat
            val thermoState = thermoId?.let { ui.states[it] }
            val thermoOk = thermoId != null && !isUnavailable(thermoState)
            val thermoPending = thermoId != null && thermoId in ui.pending
            OverviewButton(
                pending = thermoPending, dimmed = inert || !thermoOk,
                onOk = { if (!inert && thermoOk && !thermoPending) onThermostat() },
                onLeftEdge = onLeft,
                modifier = Modifier.weight(0.6f).focusRequester(thermostatFocus)
                    .then(if (scenes.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
            ) {
                Text(thermostatTileText(thermoState), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val lockId = model?.locks?.firstOrNull()
            if (lockId != null) {
                val lockState = ui.states[lockId]
                val status = lockTileStatus(lockState)
                val lockPending = lockId in ui.pending
                // Lock only: OK on a locked (or unavailable / already locking) lock does nothing.
                val canLock = status == LockTileStatus.UNLOCKED || status == LockTileStatus.JAMMED
                OverviewButton(
                    pending = lockPending, dimmed = inert || status == LockTileStatus.UNAVAILABLE,
                    onOk = { if (!inert && canLock && !lockPending) onLock(lockId) },
                    onLeftEdge = null,
                    modifier = Modifier.weight(0.4f),
                ) {
                    Text(
                        lockTileText(entityName(lockId, lockState), status),
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.weight(0.4f))
            }
        }

        // Side by side in one row: the Overview has room for one more row under the thermostat / lock.
        if (cycles.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cycles.forEach { (c, p) -> key(c.tileId) { CycleRow(c.name, p, dimmed = inert, Modifier.weight(1f)) } }
        }
    }
}

/** "Washer · 32 min left" over a thin bar (when the total is known), on resting glass. */
@Composable
private fun CycleRow(name: String, p: CycleProgress, dimmed: Boolean, modifier: Modifier) {
    Column(
        modifier.graphicsLayer { alpha = if (dimmed) 0.45f else 1f }
            .then(Glass.surface(CycleShape)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("$name · ${cycleLeftText(p.minutesLeft)}", fontSize = 13.sp, color = Jt.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.fraction?.let { BrightnessBar((it * 100).toInt()) }
    }
}

private val CycleShape = RoundedCornerShape(10.dp)

/**
 * A glass button for the Overview: [onOk] runs once per OK press, on release ([OkPress] with no hold, so key repeats
 * from a held OK do nothing). Pending pulses like a room tile; [dimmed] = 0.45 alpha.
 */
@Composable
private fun OverviewButton(
    pending: Boolean,
    dimmed: Boolean,
    onOk: () -> Unit,
    onLeftEdge: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val press = remember { OkPress() }
    DisposableEffect(Unit) { onDispose { press.reset() } }
    val ok by rememberUpdatedState(onOk)
    val leftEdge by rememberUpdatedState(onLeftEdge)
    var focused by remember { mutableStateOf(false) }

    val pulse = if (pending) {
        val tr = rememberInfiniteTransition(label = "pending")
        tr.animateFloat(0.6f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    val dim = if (dimmed) 0.45f else 1f

    Surface(
        onClick = {}, // OK is handled below (once per press); this is for focus + the glass look
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) press.reset() }
            .graphicsLayer { alpha = pulse * dim }
            .onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                when {
                    e.key == Key.DirectionLeft && leftEdge != null -> { if (e.type == KeyEventType.KeyDown) leftEdge?.invoke(); true }
                    isOk(e.key) && e.type == KeyEventType.KeyDown -> { press.onDown(native.repeatCount, false); true }
                    isOk(e.key) && e.type == KeyEventType.KeyUp -> {
                        if (press.onUp() == OkPress.Action.CLICK) ok()
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = Glass.colors(), border = Glass.border(ButtonShape), glow = Glass.glow(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) { content(focused) }
    }
}

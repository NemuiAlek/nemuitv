package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.home.Jt

private val SheetShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(99.dp)
/** The mockup's `.sheet`: solid dark glass, readable over anything (same as the brightness sheet). */
private val SheetBg = Color(0xFF161A22).copy(alpha = 0.9f)

/**
 * OK on the thermostat tile → this. ▲ ▼ = ±1° ([nudgeTemp]), ◀ ▶ = mode chip ([moveMode]); each change goes to
 * [onChange] as a new draft (nothing is sent). Every other key is swallowed. Back is left to the hub's BackHandler,
 * which sends the one climate call (or none if unchanged) and closes.
 */
@Composable
fun ThermostatSheet(draft: ThermoDraft, onChange: (ThermoDraft) -> Unit) {
    val focus = remember { FocusRequester() }
    val d by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(Unit) {
        // A few frames of retries: the sheet's focus target may not be attached on the first one.
        repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; withFrameNanos { } }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.Back -> false
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (down) change(nudgeTemp(d, up = e.key == Key.DirectionUp))
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (down) change(moveMode(d, right = e.key == Key.DirectionRight))
                        true
                    }
                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(422.dp).background(SheetBg, SheetShape).border(1.dp, Color.White.copy(alpha = 0.25f), SheetShape)
                .padding(horizontal = 34.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val canUp = nudgeTemp(draft, up = true) != draft
            val canDown = nudgeTemp(draft, up = false) != draft
            Text(thermoSheetTitle(draft), fontSize = 15.sp, color = Jt.TextSecondary, maxLines = 1)
            Text("▲", fontSize = 18.sp, color = Jt.TextPrimary.copy(alpha = if (canUp) 0.8f else 0.3f))
            Text(thermoTargetText(draft), fontSize = 56.sp, fontWeight = FontWeight.Light, color = Jt.TextPrimary)
            Text("▼", fontSize = 18.sp, color = Jt.TextPrimary.copy(alpha = if (canDown) 0.8f else 0.3f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.modes.forEach { m ->
                    val sel = m == draft.mode
                    Text(
                        "${m.icon} ${m.label}", fontSize = 13.sp, color = Jt.TextPrimary, maxLines = 1,
                        modifier = Modifier
                            .then(if (sel) Modifier.background(Color.White.copy(alpha = 0.25f), ChipShape) else Modifier)
                            .border(1.dp, Color.White.copy(alpha = if (sel) 0.6f else 0.25f), ChipShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                "▲ ▼ temperature · ◀ ▶ mode · Back saves & closes", fontSize = 12.sp, color = Jt.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

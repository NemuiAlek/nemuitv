package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
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

/** What the brightness sheet is open for, and its current (unsaved) value. */
data class SheetTarget(val entityId: String, val title: String, val start: Int, val value: Int)

private val SheetShape = RoundedCornerShape(16.dp)
/** The mockup's `.sheet`: solid dark glass, readable over anything. */
private val SheetBg = Color(0xFF161A22).copy(alpha = 0.9f)

/**
 * Hold OK on a dimmable light → this. ▲ ▼ change [SheetTarget.value] in 10 % steps ([onChange]); every other key is
 * swallowed (OK included: the hold that opened it is still down). Back is left to the hub's BackHandler, which saves
 * once and closes.
 */
@Composable
fun BrightnessSheet(target: SheetTarget, onChange: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A few frames of retries: the sheet's focus target may not be attached on the first one.
        repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; withFrameNanos { } }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.Back -> false
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (e.type == KeyEventType.KeyDown) onChange(nextBrightness(target.value, up = e.key == Key.DirectionUp))
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
            Text(target.title, fontSize = 15.sp, color = Jt.TextSecondary, maxLines = 1)
            Text("▲", fontSize = 18.sp, color = Jt.TextPrimary.copy(alpha = if (target.value < 100) 0.8f else 0.3f))
            Text("${target.value}%", fontSize = 56.sp, fontWeight = FontWeight.Light, color = Jt.TextPrimary)
            Text("▼", fontSize = 18.sp, color = Jt.TextPrimary.copy(alpha = if (target.value > 10) 0.8f else 0.3f))
            Text(
                "▲ ▼ brightness (10% steps) · Back saves & closes", fontSize = 12.sp, color = Jt.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

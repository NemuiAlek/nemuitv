package com.nemuigames.nemuitv.controlcenter

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.config.TvInput
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt

/**
 * The TV's inputs from config (e.g. Streamer · PS5 · HDMI 4); the current one is ticked and focused first.
 * OK on an input calls [onPick] (the caller switches and returns to the main page; the current input just returns).
 */
@Composable
fun InputsList(inputs: List<TvInput>, currentSource: String?, onPick: (TvInput) -> Unit) {
    val first = remember { FocusRequester() }
    val startIndex = inputs.indexOfFirst { it.source == currentSource }.coerceAtLeast(0)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // Same sizes as the Launcher settings rows: 30 dp rows, 12 sp text, 15 sp title.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Inputs", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        if (inputs.isEmpty()) {
            // Nothing to pick: the text itself holds focus so Back still reaches the panel.
            Text("No inputs in config", fontSize = 12.sp, color = Jt.TextSecondary,
                modifier = Modifier.padding(start = 4.dp).focusRequester(first).focusable())
        }
        inputs.forEachIndexed { i, input ->
            val current = input.source == currentSource
            Surface(
                onClick = { onPick(input) },
                modifier = Modifier.fillMaxWidth().height(30.dp).let { if (i == startIndex) it.focusRequester(first) else it },
                shape = ClickableSurfaceDefaults.shape(shape = CcRowShape),
                colors = Glass.colors(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                border = Glass.border(CcRowShape),
                glow = Glass.glow(),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(input.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (current) Text("✓", fontSize = 11.sp)
                    else Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}

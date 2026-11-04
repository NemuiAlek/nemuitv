package com.nemuigames.nemuitv.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp

/** Shared look: frosted glass over the moving wallpaper. */
object Jt {
    val Frost = Color.White.copy(alpha = 0.12f)
    val FrostFocused = Color.White.copy(alpha = 0.92f)
    val FrostBorder = Color.White.copy(alpha = 0.20f)
    /** Control Center "matte": a light dark tint + a bit more frost, so text reads on bright wallpapers.
     *  The Streamer has window blur off, so this stands in for a real background blur. Half the old 0.50 tint. */
    val MatteTint = Color(0xFF151A24).copy(alpha = 0.25f)
    val MatteFrost = Color.White.copy(alpha = 0.16f)
    val PanelBg = Color(0xFF151A24).copy(alpha = 0.82f)
    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.78f)
    val TextOnFocus = Color(0xFF0B0F17)
    val TileShape = RoundedCornerShape(16.dp)
    val PanelShape = RoundedCornerShape(24.dp)
    val SafeAreaH = 48.dp
    val SafeAreaV = 27.dp
    val Legible = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 12f)
}

/** Keeps remote focus inside an open overlay (tray, panel, menu) so it can't wander onto what's behind it. */
fun Modifier.trapFocus(enabled: Boolean = true): Modifier =
    this.focusProperties { onExit = { if (enabled) cancelFocusChange() } }.focusGroup()

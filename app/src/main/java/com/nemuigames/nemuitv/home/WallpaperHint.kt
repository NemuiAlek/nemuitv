package com.nemuigames.nemuitv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.ui.GuideLinks
import com.nemuigames.nemuitv.ui.GuideQr

/** The no-wallpaper hint shows only with no wallpapers installed, until dismissed, and never over the Setup checklist. */
fun showWallpaperHint(wallpaperCount: Int, dismissed: Boolean, checklistOpen: Boolean): Boolean =
    wallpaperCount == 0 && !dismissed && !checklistOpen

/**
 * A small glass card above the dock: where to read about adding wallpapers (text + QR) and a focusable `Got it`.
 * It never asks for focus itself; [gotItFocus] lets Home move focus here. [onKey] sees the remote keys first (▲ / ▼ routing).
 */
@Composable
fun WallpaperHint(gotItFocus: FocusRequester, onKey: (KeyEvent) -> Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.then(Glass.matte(Jt.PanelShape)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuideQr(GuideLinks.WALLPAPERS, 64.dp)
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add your own video wallpapers, see the guide", fontSize = 14.sp, color = Jt.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(shadow = Jt.Legible))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(gotItFocus).onPreviewKeyEvent(onKey),
                shape = ClickableSurfaceDefaults.shape(shape = Jt.TileShape),
                colors = Glass.colors(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                border = Glass.border(Jt.TileShape),
            ) {
                Text("Got it", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }
        }
    }
}

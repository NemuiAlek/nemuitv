package com.nemuigames.nemuitv.tray

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.apps.AppInfo
import com.nemuigames.nemuitv.home.FrostTile
import com.nemuigames.nemuitv.home.HiddenGlyph
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.home.trapFocus

/** The tray's last tile (can't be moved): opens the [HiddenAppsPanel]. */
@Composable
fun HiddenAppsTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FrostTile(onClick = onClick, modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HiddenGlyph(LocalContentColor.current, size = 28.dp)
                Text("Hidden apps", fontSize = 11.sp)
            }
        }
    }
}

/** Focus target for the "No hidden apps" row. */
private const val EMPTY_ROW = "__empty"

/**
 * Frosted list of hidden apps (logo + name). OK on one shows a small **Show in tray** row under it; OK there unhides
 * the app (it goes to the end of the tray). Back closes the confirm row first, then the panel.
 */
@Composable
fun HiddenAppsPanel(
    apps: List<AppInfo>,
    icon: (AppInfo) -> ImageBitmap,
    onShow: (pkg: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf<String?>(null) }
    // Which row to focus once the list has (re)drawn: a package, EMPTY_ROW, or null = nothing pending.
    var focusTarget by remember { mutableStateOf<String?>(apps.firstOrNull()?.packageName ?: EMPTY_ROW) }
    val rowFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun focusOf(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    BackHandler {
        val pkg = confirming
        if (pkg != null) { confirming = null; focusTarget = pkg } else onDismiss()
    }
    // The row may not be laid out yet (the list changes a frame after an unhide): retry for a few frames, and only
    // then fall back to the first row (or "No hidden apps"), so focus never stays on a row that just went away.
    LaunchedEffect(focusTarget, apps) {
        val target = focusTarget ?: return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { focusOf(target).requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                focusTarget = null
                return@LaunchedEffect
            }
        }
        runCatching { focusOf(apps.firstOrNull()?.packageName ?: EMPTY_ROW).requestFocus() }
        focusTarget = null
    }
    LaunchedEffect(confirming) {
        if (confirming != null) { withFrameNanos { }; runCatching { confirmFocus.requestFocus() } }
    }

    val guard = remember { MenuKeyGuard() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).onPreviewKeyEvent {
            guard.swallow(
                isDown = it.type == KeyEventType.KeyDown,
                isOk = it.key == Key.DirectionCenter || it.key == Key.Enter,
                repeatCount = it.nativeKeyEvent.repeatCount,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(320.dp).background(Jt.PanelBg, Jt.PanelShape).padding(16.dp).trapFocus()) {
            Text("Hidden apps", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (apps.isEmpty()) {
                    MenuButton("No hidden apps", Modifier.focusRequester(focusOf(EMPTY_ROW))) { onDismiss() }
                }
                apps.forEachIndexed { i, app ->
                    MenuButton(
                        app.label,
                        Modifier.focusRequester(focusOf(app.packageName)),
                        leading = { Image(icon(app), contentDescription = null, modifier = Modifier.size(24.dp)) },
                    ) { confirming = if (confirming == app.packageName) null else app.packageName }
                    if (confirming == app.packageName) {
                        MenuButton("Show in tray", Modifier.padding(start = 34.dp).focusRequester(confirmFocus)) {
                            val rest = apps.filter { it.packageName != app.packageName }
                            focusTarget = (rest.getOrNull(i) ?: rest.getOrNull(i - 1))?.packageName ?: EMPTY_ROW
                            confirming = null
                            onShow(app.packageName)
                        }
                    }
                }
            }
        }
    }
}

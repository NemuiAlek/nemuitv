package com.nemuigames.nemuitv.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.setup.Row as SetupRow
import com.nemuigames.nemuitv.setup.RowKind
import com.nemuigames.nemuitv.setup.SetupChecklist
import com.nemuigames.nemuitv.setup.SetupInputs
import com.nemuigames.nemuitv.setup.SetupStore
import com.nemuigames.nemuitv.setup.SystemSwitches
import com.nemuigames.nemuitv.ui.GuideQr
import com.nemuigames.nemuitv.wallpaper.listWallpapers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Switch rows the app can't flip itself. Android's notification manager keeps its own allowed-listener list and ignores
 * a plain write to enabled_notification_listeners until a reboot (checked on the emulator), so Now Playing shows its
 * state only; the computer setup turns it on properly (`cmd notification allow_listener`). The accessibility service
 * and the screensaver do take effect at once.
 */
private val StatusOnly: Set<RowKind> = setOf(RowKind.NOW_PLAYING)

/**
 * Launcher settings › Setup: what the one-time computer setup gave us and what is still missing. The three switch rows
 * flip right here (OK); a row with a guide shows its QR while focused. "Done" stores that the checklist was seen, so
 * it stops opening by itself on launch. Back → Launcher settings.
 */
@Composable
fun SetupPage(core: NemuiCore, firstRowFocus: FocusRequester, onDone: () -> Unit) {
    val context = LocalContext.current
    val switches = remember { SystemSwitches(context) }
    val store = remember { SetupStore(context) }
    val scope = rememberCoroutineScope()
    val haStatus by core.ha.status.collectAsStateWithLifecycle()
    val config by core.configStore.state.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    // Device state is re-read after each toggle (Android applies the write a moment later).
    val inputs = remember(tick, haStatus, config) {
        SetupInputs(
            secureGranted = switches.secureGranted(),
            takeoverOn = switches.takeoverOn(),
            nowPlayingOn = switches.nowPlayingOn(),
            screensaverOurs = switches.screensaverOurs(),
            haStatus = haStatus,
            haProblem = when (haStatus) {
                HaStatus.AUTH_FAILED -> "Token rejected"
                HaStatus.DISCONNECTED -> "Offline, reconnecting…"
                HaStatus.CONNECTING -> "Connecting…"
                else -> null
            },
            wallpaperCount = listWallpapers(core.wallpaperDir).size,
            configIssues = when (val c = config) {
                is ConfigResult.Ok -> c.issues
                is ConfigResult.Invalid -> listOf(c.reason)
                ConfigResult.Missing -> emptyList()
            },
        )
    }
    val rows = SetupChecklist.rows(inputs, StatusOnly)
    var focusedGuide by remember { mutableStateOf<String?>(null) }

    fun toggle(row: SetupRow) {
        val on = row.switchOn != true
        scope.launch {
            when (row.kind) {
                RowKind.TAKEOVER -> switches.setTakeover(on)
                RowKind.NOW_PLAYING -> switches.setNowPlaying(on)
                RowKind.SCREENSAVER -> switches.setScreensaver(on, store)
                else -> Unit
            }
            delay(500)
            tick++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle("Setup")
        rows.forEachIndexed { index, row ->
            SetupRowView(
                row,
                modifier = (if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier)
                    .onFocusChanged { f ->
                        if (f.isFocused) focusedGuide = row.guideUrl
                        else if (focusedGuide == row.guideUrl) focusedGuide = null
                    },
                onClick = { if (row.switchOn != null && row.enabled) toggle(row) },
            )
        }
        focusedGuide?.let {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { GuideQr(it, 76.dp) }
        }
        SettingRow("Done", "", Modifier) {
            scope.launch { store.setChecklistDone(true); onDone() }
        }
    }
}

@Composable
private fun SetupRowView(row: SetupRow, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = SettingRowHeight),
        shape = ClickableSurfaceDefaults.shape(shape = CcRowShape),
        colors = Glass.colors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = Glass.border(CcRowShape),
        glow = Glass.glow(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.detail, fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            row.switchOn?.let {
                Spacer(Modifier.width(8.dp))
                Text(if (it) "✓" else "✗", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (it) Jt.TextPrimary else LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
    }
}

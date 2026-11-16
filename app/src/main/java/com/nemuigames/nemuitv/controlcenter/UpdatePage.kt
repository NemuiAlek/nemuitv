package com.nemuigames.nemuitv.controlcenter

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.update.UpdateState
import com.nemuigames.nemuitv.update.stripMarkdown

/** The button's label and whether it can be pressed, for each installer state. */
fun updateButton(state: UpdateState): Pair<String, Boolean> = when (state) {
    UpdateState.NeedsPermission -> "Allow installs first" to true
    is UpdateState.Downloading -> "Downloading… ${state.percent}%" to false
    UpdateState.Installing -> "Installing…" to false
    else -> "Install" to true // Idle, Cancelled, Mismatch, DownloadFailed, InstallFailed
}

/** The line under the notes, null while nothing needs saying. */
fun updateMessage(state: UpdateState): String? = when (state) {
    UpdateState.NeedsPermission -> "Turn on 'Install unknown apps' for NemuiTV, then come back."
    UpdateState.Mismatch -> "Download didn't check out. Try again."
    UpdateState.DownloadFailed -> "Download failed. Try again."
    UpdateState.Cancelled -> "Cancelled. Press Install to try again."
    is UpdateState.InstallFailed -> "Couldn't install: ${state.reason}"
    UpdateState.Idle, is UpdateState.Downloading, UpdateState.Installing -> null
}

/**
 * Launcher settings › Update Available!: the new version, its release notes (plain text, scrolls when long), a message
 * about the last attempt and one button. Focus starts on the button ([firstRowFocus]). Back → Launcher settings.
 */
@Composable
fun UpdatePage(core: NemuiCore, firstRowFocus: FocusRequester) {
    val release by core.update.available.collectAsStateWithLifecycle()
    val state by core.update.state.collectAsStateWithLifecycle()
    val (label, enabled) = updateButton(state)
    val message = updateMessage(state)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle("NemuiTV ${release?.versionText.orEmpty()}")
        // Focusable so Up from the button lets the remote scroll long notes.
        Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).focusable().padding(horizontal = 4.dp)) {
            Text(stripMarkdown(release?.notes.orEmpty()), fontSize = 11.sp, color = Jt.TextSecondary)
        }
        if (message != null) {
            Text(message, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))
        }
        SettingRow(label, "", Modifier.focusRequester(firstRowFocus).padding(top = 2.dp), enabled = enabled) {
            if (state == UpdateState.NeedsPermission) core.update.onAllowPressed() else core.update.onInstallPressed()
        }
    }
}

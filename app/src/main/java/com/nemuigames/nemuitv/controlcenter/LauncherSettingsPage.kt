package com.nemuigames.nemuitv.controlcenter

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.nemuigames.nemuitv.ambient.minutesLabel
import com.nemuigames.nemuitv.ambient.nextAmbientAfter
import com.nemuigames.nemuitv.ambient.nextBlackAfter
import com.nemuigames.nemuitv.ambient.nextSleepAfter
import com.nemuigames.nemuitv.ambient.sleepsBeforeBlack
import com.nemuigames.nemuitv.ha.HaPresence
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.config.WallpaperOrder
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.settings.clockLabel
import com.nemuigames.nemuitv.settings.configProblem
import com.nemuigames.nemuitv.settings.timerNote
import com.nemuigames.nemuitv.setup.SystemSwitches
import com.nemuigames.nemuitv.settings.everyLabel
import com.nemuigames.nemuitv.settings.lofiLabel
import com.nemuigames.nemuitv.settings.nextClock
import com.nemuigames.nemuitv.settings.nextUnit
import com.nemuigames.nemuitv.settings.nextWallpaperEvery
import com.nemuigames.nemuitv.settings.onOffLabel
import com.nemuigames.nemuitv.settings.orderLabel
import com.nemuigames.nemuitv.settings.statusLine
import com.nemuigames.nemuitv.settings.unitLabel

private const val TAKEOVER_SERVICE = "com.nemuigames.nemuitv/com.nemuigames.nemuitv.takeover.HomeTakeoverService"

/**
 * The Control Center's "Launcher settings" sub-page (the gear circle): two buckets (Wallpaper ›, Screensaver ›; the list had
 * grown to 13 rows), Clock and Weather unit (OK cycles the value: applies at once, saved on the
 * TV), Wi-Fi settings (Android's page) and Advanced settings (Google's Settings; both close the panel first), then
 * read-only About. [focus] goes on the bucket we came back from ([cameFrom]), else on the first row.
 */
@Composable
fun LauncherSettingsPage(
    core: NemuiCore,
    focus: FocusRequester,
    cameFrom: CcPage?,
    onOpen: (CcPage) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by core.settings.prefs.collectAsStateWithLifecycle()
    val config by core.configStore.state.collectAsStateWithLifecycle()
    val haStatus by core.ha.status.collectAsStateWithLifecycle()
    val weatherProblem by core.ha.weatherProblem.collectAsStateWithLifecycle()
    val update by core.update.available.collectAsStateWithLifecycle()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val takeoverOn = remember { takeoverEnabled(context) }
    fun focusIf(page: CcPage) = if (page == (cameFrom ?: CcPage.WALLPAPER_SETTINGS)) Modifier.focusRequester(focus) else Modifier

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle("Launcher settings")
        SettingRow("Wallpaper", "›", focusIf(CcPage.WALLPAPER_SETTINGS)) { onOpen(CcPage.WALLPAPER_SETTINGS) }
        SettingRow("Screensaver", "›", focusIf(CcPage.SCREENSAVER_SETTINGS)) { onOpen(CcPage.SCREENSAVER_SETTINGS) }
        SettingRow("Setup", "›", focusIf(CcPage.SETUP)) { onOpen(CcPage.SETUP) }
        SettingRow("Clock", clockLabel(prefs.clock)) { core.settings.update { it.copy(clock = nextClock(it.clock)) } }
        // Weather and Lofi come from Home Assistant; without it these rows would only read "None" / "not set up".
        if (HaPresence.showsHaUi(haStatus)) {
            SettingRow("Weather unit", unitLabel(prefs.unit)) { core.settings.update { it.copy(unit = nextUnit(it.unit)) } }
        }
        SettingRow("Wi-Fi settings", "›") {
            onClose() // overlay host: hidden before the settings screen starts
            context.openWifiSettings()
        }
        SettingRow("Advanced settings", "›") {
            onClose()
            context.openSettings()
        }
        Spacer(Modifier.height(2.dp))
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("About", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary)
            val lines = listOfNotNull(
                statusLine(version, haStatus),
                configProblem(config),
                weatherProblem,
                "Home-button takeover: " + if (takeoverOn) "On" else "Off",
            )
            lines.forEach { Text(it, fontSize = 11.sp, color = Jt.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        // Only when a newer release is known; the one place the app announces it.
        update?.let { SettingRow("Update Available!", "${it.versionText} ›", focusIf(CcPage.UPDATE), yellow = true) { onOpen(CcPage.UPDATE) } }
    }
}

/** Launcher settings › Wallpaper: order, new one on return Home, timed change. Back → Launcher settings. */
@Composable
fun WallpaperSettingsPage(core: NemuiCore, firstRowFocus: FocusRequester) {
    val prefs by core.settings.prefs.collectAsStateWithLifecycle()
    val config by core.configStore.state.collectAsStateWithLifecycle()
    val configOrder = (config as? ConfigResult.Ok)?.config?.wallpaperOrder ?: WallpaperOrder.SHUFFLE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle("Wallpaper")
        SettingRow("Order", orderLabel(prefs.effectiveOrder(configOrder)), Modifier.focusRequester(firstRowFocus)) {
            core.settings.update { it.cycleOrder(configOrder) }
        }
        SettingRow("On return Home", onOffLabel(prefs.wallpaperOnReturn)) {
            core.settings.update { it.copy(wallpaperOnReturn = !it.wallpaperOnReturn) }
        }
        SettingRow("Change every", everyLabel(prefs.wallpaperEveryMin)) {
            core.settings.update { it.copy(wallpaperEveryMin = nextWallpaperEvery(it.wallpaperEveryMin)) }
        }
    }
}

/** Launcher settings › Screensaver: ambient/black/sleep timers, Lofi, and their warnings. Back → Launcher settings. */
@Composable
fun ScreensaverSettingsPage(core: NemuiCore, firstRowFocus: FocusRequester) {
    val prefs by core.settings.prefs.collectAsStateWithLifecycle()
    val timerWrite by core.timerWrite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-checked with each timer result: the grant only changes from a computer, which also re-runs the timers.
    val secureGranted = remember(timerWrite) { SystemSwitches(context).secureGranted() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle("Screensaver")
        SettingRow("Ambient after", minutesLabel(prefs.ambientAfterMin), Modifier.focusRequester(firstRowFocus)) {
            core.settings.update { it.copy(ambientAfterMin = nextAmbientAfter(it.ambientAfterMin)) }
        }
        SettingRow("Black after", minutesLabel(prefs.blackAfterMin)) {
            core.settings.update { it.copy(blackAfterMin = nextBlackAfter(it.blackAfterMin)) }
        }
        SettingRow("Sleep after", minutesLabel(prefs.sleepAfterMin)) {
            core.settings.update { it.copy(sleepAfterMin = nextSleepAfter(it.sleepAfterMin)) }
        }
        // Lofi when ambient starts from Home (was the Control Center's 5th circle until couch round 1, 11-02).
        val lofiSetUp = core.lofiConfig != null
        val haStatus by core.ha.status.collectAsStateWithLifecycle()
        if (HaPresence.showsHaUi(haStatus)) {
            SettingRow("Lofi", lofiLabel(prefs.lofiOn, lofiSetUp)) {
                if (lofiSetUp) core.settings.update { it.copy(lofiOn = !it.lofiOn) }
            }
        }
        val notes = listOfNotNull(
            if (sleepsBeforeBlack(prefs)) "Sleeps before black starts" else null,
            timerNote(timerWrite, secureGranted),
        )
        if (notes.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                notes.forEach { Text(it, fontSize = 11.sp, color = Jt.TextSecondary, maxLines = 1) }
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String) =
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))

@Composable
internal fun SettingRow(label: String, value: String, modifier: Modifier = Modifier, yellow: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() }, // stays focusable while disabled so Back/focus never get stuck
        // Rows were "way too large" on the couch: 30 dp rows, was 40.
        modifier = modifier.fillMaxWidth().height(SettingRowHeight),
        shape = ClickableSurfaceDefaults.shape(shape = CcRowShape),
        colors = if (yellow) Glass.yellowColors() else Glass.colors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = if (yellow) Glass.yellowBorder(CcRowShape) else Glass.border(CcRowShape),
        glow = Glass.glow(),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(value, fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

internal val SettingRowHeight = 30.dp

/** Android's Wi-Fi page, or its main settings if this box has none. */
private fun Context.openWifiSettings() {
    val wifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(wifi) }.onFailure {
        Log.w("NemuiTV", "no Wi-Fi settings screen, opening Settings", it)
        openSettings()
    }
}

/** Google's Settings (the old "All Settings" tile). */
private fun Context.openSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w("NemuiTV", "couldn't open Settings", it) }
}

/** Whether NemuiTV's Home-button service is switched on in Accessibility. */
private fun takeoverEnabled(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { it.equals(TAKEOVER_SERVICE, ignoreCase = true) } == true

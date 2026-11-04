package com.nemuigames.nemuitv.settings

import com.nemuigames.nemuitv.ambient.TimerWrite
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.config.WallpaperOrder
import com.nemuigames.nemuitv.ha.HaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Clock format: follow Android's 12/24h setting, or force one. */
enum class ClockMode { SYSTEM, H12, H24 }

/** Unit shown after the temperature: none (just "°", as before), °F or °C (converted if HA uses the other one). */
enum class TempUnit { NONE, F, C }

/**
 * The Launcher settings page's values (saved on the TV). Each default is the behaviour from before the page existed:
 * [wallpaperOrder] null = config.json's order; [wallpaperOnReturn] = new wallpaper each time Home comes back;
 * [wallpaperEveryMin] = "Also change every" N min while Home shows (null = Off). Both can be on.
 */
data class LauncherPrefs(
    val wallpaperOrder: WallpaperOrder?,
    val wallpaperOnReturn: Boolean,
    val wallpaperEveryMin: Int?,
    val clock: ClockMode,
    val unit: TempUnit,
    val ambientAfterMin: Int = 5,
    val blackAfterMin: Int = 30,
    /** -1 = Never. */
    val sleepAfterMin: Int = 240,
    val lofiOn: Boolean = true,
) {
    fun effectiveOrder(configOrder: WallpaperOrder): WallpaperOrder = wallpaperOrder ?: configOrder

    /** OK on "Wallpaper order": flips what is in use now (the config's order until it was set here). */
    fun cycleOrder(configOrder: WallpaperOrder): LauncherPrefs = copy(
        wallpaperOrder = if (effectiveOrder(configOrder) == WallpaperOrder.SHUFFLE) WallpaperOrder.SEQUENTIAL else WallpaperOrder.SHUFFLE,
    )

    companion object {
        val DEFAULT = LauncherPrefs(
            wallpaperOrder = null, wallpaperOnReturn = true, wallpaperEveryMin = null, clock = ClockMode.SYSTEM, unit = TempUnit.NONE,
        )
    }
}

/** The two wallpaper-change settings as stored. */
data class WallpaperChange(val onReturn: Boolean, val everyMin: Int?)

/**
 * Reads the wallpaper-change settings, migrating the old single "Wallpaper change" value ([legacyEveryMin]: null =
 * "Each return Home", N = "Every N min") when the new ones were never saved ([onReturn] null): each return → (On, Off),
 * every N min → (Off, N) — what they chose before.
 */
fun wallpaperChangeFromStore(onReturn: Boolean?, everyMin: Int?, legacyEveryMin: Int?): WallpaperChange {
    if (onReturn != null) return WallpaperChange(onReturn, everyMin?.takeIf { it in WALLPAPER_EVERY_CHOICES })
    val legacy = legacyEveryMin?.takeIf { it in WALLPAPER_EVERY_CHOICES }
    return if (legacy != null) WallpaperChange(onReturn = false, everyMin = legacy) else WallpaperChange(onReturn = true, everyMin = null)
}

/** "Also change every" choices in OK order: Off (null) → 5 → 15 → 30 min → Off. */
val WALLPAPER_EVERY_CHOICES: List<Int?> = listOf(null, 5, 15, 30)

internal fun <T> List<T>.after(value: T): T = this[(indexOf(value) + 1).mod(size)] // unknown value → first choice

fun nextWallpaperEvery(current: Int?): Int? = WALLPAPER_EVERY_CHOICES.after(current)
fun nextClock(current: ClockMode): ClockMode = ClockMode.entries.after(current)
fun nextUnit(current: TempUnit): TempUnit = TempUnit.entries.after(current)

fun orderLabel(order: WallpaperOrder) = if (order == WallpaperOrder.SHUFFLE) "Shuffle" else "In order"
fun everyLabel(minutes: Int?) = if (minutes == null) "Off" else "$minutes min"
fun onOffLabel(on: Boolean) = if (on) "On" else "Off"
/** The Lofi row: On / Off, or "Lofi not set up" when the config has no usable `lofi` block (OK does nothing then). */
fun lofiLabel(on: Boolean, setUp: Boolean) = if (setUp) onOffLabel(on) else "Lofi not set up"
fun clockLabel(mode: ClockMode) = when (mode) { ClockMode.SYSTEM -> "System"; ClockMode.H12 -> "12h"; ClockMode.H24 -> "24h" }
fun unitLabel(unit: TempUnit) = when (unit) { TempUnit.NONE -> "None"; TempUnit.F -> "°F"; TempUnit.C -> "°C" }

/** Time pattern for the home clock. [system24h] = Android's own 12/24h setting. */
fun clockPattern(mode: ClockMode, system24h: Boolean): String = when (mode) {
    ClockMode.H24 -> "HH:mm"
    ClockMode.H12 -> "h:mm a"
    ClockMode.SYSTEM -> if (system24h) "HH:mm" else "h:mm a"
}

/**
 * Weather temperature text. [haUnit] is HA's `temperature_unit` ("°F", "°C", or "°" when it sent none).
 * NONE = as before (`72°`). F / C = converted when HA uses the other unit, rounded, with the unit (`22°C`).
 */
fun formatTemp(value: Double, haUnit: String, mode: TempUnit): String {
    val from = when {
        haUnit.contains('F', ignoreCase = true) -> TempUnit.F
        haUnit.contains('C', ignoreCase = true) -> TempUnit.C
        else -> null // unknown: nothing to convert from
    }
    val shown = when {
        mode == TempUnit.F && from == TempUnit.C -> value * 9.0 / 5.0 + 32.0
        mode == TempUnit.C && from == TempUnit.F -> (value - 32.0) * 5.0 / 9.0
        else -> value
    }
    val n = shown.roundToInt()
    return when (mode) {
        TempUnit.NONE -> "$n°"
        TempUnit.F -> "$n°F"
        TempUnit.C -> "$n°C"
    }
}

/** How often the wallpaper changes while Home is showing; null = no timer ("Also change every: Off"). */
fun timerPeriodMs(prefs: LauncherPrefs): Long? = prefs.wallpaperEveryMin?.let { it * 60_000L }

/** Leaving Home picks the next wallpaper iff "New wallpaper on return Home" is On (whatever the timer does). */
fun advanceOnLeave(prefs: LauncherPrefs): Boolean = prefs.wallpaperOnReturn

/**
 * When the wallpaper timer next fires, or null = no timer. Any (re)start — Home shown again, setting changed — counts
 * a full period from [now]; the old schedule is dropped.
 */
fun nextRotationAt(now: Long, periodMs: Long?, homeVisible: Boolean): Long? =
    if (periodMs == null || !homeVisible) null else now + periodMs

/** The one wallpaper timer: every [restart] cancels the previous one first, so two never run at once. */
class RotationTimer(private val scope: CoroutineScope, private val onTick: () -> Unit) {
    private var job: Job? = null

    fun restart(periodMs: Long?, homeVisible: Boolean) {
        job?.cancel()
        job = null
        if (periodMs == null || nextRotationAt(0L, periodMs, homeVisible) == null) return
        job = scope.launch {
            while (true) { delay(periodMs); onTick() }
        }
    }

    fun stop() = restart(null, homeVisible = false)
}

/** About: `NemuiTV <version> · HA: <status>` (from the M1 mini panel). */
fun statusLine(versionName: String, haStatus: HaStatus): String = "NemuiTV $versionName · HA: " + when (haStatus) {
    HaStatus.CONNECTED -> "Connected"
    HaStatus.CONNECTING -> "Connecting…"
    HaStatus.DISCONNECTED -> "Offline — reconnecting…"
    HaStatus.AUTH_FAILED -> "Token rejected"
    HaStatus.NOT_CONFIGURED -> "Not configured"
}

/**
 * The Screensaver page's hint when Android's timers couldn't be written. Without WRITE_SECURE_SETTINGS (or the
 * settings-write grant) only the computer setup can fix it; with it, the Setup page's Screensaver switch can.
 */
fun timerNote(write: TimerWrite, secureGranted: Boolean): String? = when (write) {
    TimerWrite.OK -> null
    TimerWrite.NOT_SCREENSAVER -> if (secureGranted) "Turn on Screensaver in Setup" else "Run setup from a computer"
    TimerWrite.NO_PERMISSION -> "Run setup from a computer"
}

fun configProblem(config: ConfigResult): String? = when (config) {
    is ConfigResult.Ok -> config.issues.size.let { n -> if (n == 0) null else "Config: $n problem${if (n == 1) "" else "s"}" }
    ConfigResult.Missing -> null // no config.json is the normal setup without Home Assistant
    is ConfigResult.Invalid -> "Config invalid: ${config.reason}"
}

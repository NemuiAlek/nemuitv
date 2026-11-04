package com.nemuigames.nemuitv.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nemuigames.nemuitv.ambient.AMBIENT_AFTER_CHOICES
import com.nemuigames.nemuitv.ambient.BLACK_AFTER_CHOICES
import com.nemuigames.nemuitv.ambient.SLEEP_AFTER_CHOICES
import com.nemuigames.nemuitv.config.WallpaperOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val Context.launcherSettingsDataStore by preferencesDataStore(name = "launcher_settings")

/**
 * The Launcher settings page's values, saved on the TV. Owned by NemuiCore so the home screen and the Home-button
 * overlay share them. [prefs] starts at [LauncherPrefs.DEFAULT] (= the behaviour before these settings existed) and
 * switches to the saved values once DataStore has been read (a few ms at app start).
 */
class LauncherSettings(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.launcherSettingsDataStore
    private val orderKey = stringPreferencesKey("wallpaper_order")
    private val onReturnKey = booleanPreferencesKey("wallpaper_on_return")
    private val everyKey = intPreferencesKey("wallpaper_timer_min")
    /** The old single "Wallpaper change" value (null = each return Home); read once to migrate, removed on save. */
    private val legacyEveryKey = intPreferencesKey("wallpaper_every_min")
    private val clockKey = stringPreferencesKey("clock")
    private val unitKey = stringPreferencesKey("unit")
    private val ambientAfterKey = intPreferencesKey("ambient_after_min")
    private val blackAfterKey = intPreferencesKey("black_after_min")
    private val sleepAfterKey = intPreferencesKey("sleep_after_min")
    private val lofiOnKey = booleanPreferencesKey("lofi_on")

    private val _prefs = MutableStateFlow(LauncherPrefs.DEFAULT)
    val prefs: StateFlow<LauncherPrefs> = _prefs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once the saved values have been read (or failed to), so [prefs] is no longer just the defaults. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // Changes show at once from _prefs; saving runs behind, latest value wins (quick OK presses never go backwards).
    private val toSave = MutableStateFlow<LauncherPrefs?>(null)
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = runCatching { read(store.data.first()) }
                .onFailure { Log.w("NemuiTV", "launcher settings unreadable, using defaults", it) }
                .getOrNull()
            if (saved != null && !changedBeforeLoad) _prefs.value = saved
            _loaded.value = true
            toSave.filterNotNull().collect { p ->
                runCatching { store.edit { write(it, p) } }.onFailure { Log.w("NemuiTV", "couldn't save launcher settings", it) }
            }
        }
    }

    /** Applies [change] now and saves it. */
    fun update(change: (LauncherPrefs) -> LauncherPrefs) {
        val next = change(_prefs.value)
        if (next == _prefs.value) return
        changedBeforeLoad = true
        _prefs.value = next
        toSave.value = next
    }

    private fun read(p: Preferences): LauncherPrefs {
        val change = wallpaperChangeFromStore(p[onReturnKey], p[everyKey], legacyEveryMin = p[legacyEveryKey])
        return LauncherPrefs(
            wallpaperOrder = p[orderKey]?.let { v -> WallpaperOrder.entries.firstOrNull { it.name == v } },
            wallpaperOnReturn = change.onReturn,
            wallpaperEveryMin = change.everyMin,
            clock = p[clockKey]?.let { v -> ClockMode.entries.firstOrNull { it.name == v } } ?: ClockMode.SYSTEM,
            unit = p[unitKey]?.let { v -> TempUnit.entries.firstOrNull { it.name == v } } ?: TempUnit.NONE,
            ambientAfterMin = p[ambientAfterKey]?.takeIf { it in AMBIENT_AFTER_CHOICES } ?: LauncherPrefs.DEFAULT.ambientAfterMin,
            blackAfterMin = p[blackAfterKey]?.takeIf { it in BLACK_AFTER_CHOICES } ?: LauncherPrefs.DEFAULT.blackAfterMin,
            sleepAfterMin = p[sleepAfterKey]?.takeIf { it in SLEEP_AFTER_CHOICES } ?: LauncherPrefs.DEFAULT.sleepAfterMin,
            lofiOn = p[lofiOnKey] ?: LauncherPrefs.DEFAULT.lofiOn,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, v: LauncherPrefs) {
        if (v.wallpaperOrder == null) p.remove(orderKey) else p[orderKey] = v.wallpaperOrder.name
        p[onReturnKey] = v.wallpaperOnReturn // always written: marks the new form as saved (see wallpaperChangeFromStore)
        if (v.wallpaperEveryMin == null) p.remove(everyKey) else p[everyKey] = v.wallpaperEveryMin
        p.remove(legacyEveryKey)
        p[clockKey] = v.clock.name
        p[unitKey] = v.unit.name
        p[ambientAfterKey] = v.ambientAfterMin
        p[blackAfterKey] = v.blackAfterMin
        p[sleepAfterKey] = v.sleepAfterMin
        p[lofiOnKey] = v.lofiOn
    }
}

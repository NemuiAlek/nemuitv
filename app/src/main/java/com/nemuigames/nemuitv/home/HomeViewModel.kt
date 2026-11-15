package com.nemuigames.nemuitv.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nemuigames.nemuitv.apps.AppInfo
import com.nemuigames.nemuitv.apps.AppRepository
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.config.WallpaperOrder
import com.nemuigames.nemuitv.favorites.AppLayout
import com.nemuigames.nemuitv.favorites.AppLayoutStore
import com.nemuigames.nemuitv.favorites.AppOrder
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.ha.WeatherSnapshot
import com.nemuigames.nemuitv.ha.HaPresence
import com.nemuigames.nemuitv.settings.RotationTimer
import com.nemuigames.nemuitv.settings.advanceOnLeave
import com.nemuigames.nemuitv.settings.timerPeriodMs
import com.nemuigames.nemuitv.wallpaper.PosterCache
import com.nemuigames.nemuitv.wallpaper.WallpaperPlaylist
import com.nemuigames.nemuitv.wallpaper.listWallpapers
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "NemuiTV"

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val filesDir: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val wallpaperDir: File = File(filesDir, "wallpapers").apply { mkdirs() }

    private val core = app.core
    private val configStore = core.configStore
    private val layoutStore = AppLayoutStore(app)
    private val appRepo = AppRepository(app, viewModelScope)
    private val ha = core.ha

    val config: StateFlow<ConfigResult> = configStore.state
    val apps: StateFlow<List<AppInfo>> = appRepo.apps
    val weather: StateFlow<WeatherSnapshot?> = ha.weather
    val haStatus: StateFlow<HaStatus> = ha.status
    val weatherProblem: StateFlow<String?> = ha.weatherProblem
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000) } }
    val weatherStale: StateFlow<Boolean> = combine(ha.status, ha.disconnectedSince, minuteTicker) { status, since, _ ->
        HaPresence.showsProblem(status, since, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Edits show instantly from `pendingLayout` while DataStore saves in the background; otherwise quick ◀ ▶ presses
    // in move mode would each start from the not-yet-saved layout and skip steps.
    private val pendingLayout = MutableStateFlow<AppLayout?>(null)

    /** Saved layout (order + hidden), with unsaved edits on top; null until first-run defaults are saved. */
    private val layout: StateFlow<AppLayout?> =
        combine(layoutStore.layout, pendingLayout) { saved, pending -> pending ?: saved.layout }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Visible entries in saved order: the tray grid (⌂ is one of them, a stand-in [HOME_APP]). The first six are the favorites (the dock). */
    val trayApps: StateFlow<List<AppInfo>> = combine(layout, apps) { l, installed ->
        if (l == null || installed.isEmpty()) return@combine emptyList()
        val byPkg = installed.associateBy { it.packageName } + (AppOrder.HOME_ID to HOME_APP)
        AppOrder.visible(l, byPkg.keys).mapNotNull(byPkg::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dock: StateFlow<List<AppInfo>> = trayApps.map { l -> l.take(AppOrder.favorites(l.map { it.packageName }).size) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Installed apps the user hid, in label order (the "Hidden apps" list). */
    val hiddenApps: StateFlow<List<AppInfo>> = combine(layout, apps) { l, installed ->
        if (l == null) emptyList() else installed.filter { it.packageName in l.hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _wallpaper = MutableStateFlow<String?>(null)
    val wallpaper: StateFlow<String?> = _wallpaper.asStateFlow()
    private var playlist: WallpaperPlaylist? = null
    private var playlistKey: Pair<List<String>, WallpaperOrder>? = null

    // "Also change every N min": one timer, only while Home is showing (none when Off).
    private var homeVisible = false
    private val rotation = RotationTimer(viewModelScope) { nextWallpaper() }
    private fun restartRotation() = rotation.restart(timerPeriodMs(core.settings.prefs.value), homeVisible)

    // Still of the current wallpaper's first frame, shown while the video starts (see PosterCache).
    private val posters = PosterCache(File(app.cacheDir, "posters"))
    private val _poster = MutableStateFlow<Bitmap?>(null)
    val wallpaperPoster: StateFlow<Bitmap?> = _poster.asStateFlow()

    /**
     * Home is showing again after the screensaver: HomeScreen draws a black cover until the wallpaper is up, then
     * fades it out ([com.nemuigames.nemuitv.ambient.homeCoverLifts]). Compose state so the first frame already has it.
     */
    var homeCover by mutableStateOf(false)
        private set
    fun coverAfterDream() { homeCover = true }
    fun coverLifted() { homeCover = false }
    private var posterJob: Job? = null

    init {
        // The screensaver starts on whatever Home is showing.
        viewModelScope.launch { _wallpaper.collect { core.currentWallpaper.value = it } }
        viewModelScope.launch {
            _wallpaper.collectLatest { f ->
                _poster.value = null
                _poster.value = f?.let { withContext(Dispatchers.IO) { posters.load(File(it)) } }
            }
        }
        // First run / upgrade: save a layout once. This reads the DataStore flow directly (its first value is the
        // real stored value), never the StateFlow above whose initial null just means "not read yet" — otherwise
        // a fast app-list load at boot could look like "never saved" and overwrite the user's layout.
        // Once Home Assistant is configured (config.json often arrives after the first run), a first-run layout that
        // still has ⌂ where the no-HA defaults put it gets ⌂ in the favorites, once (AppOrder.promoteHome).
        viewModelScope.launch {
            val haSetUp = configStore.state.map { (it as? ConfigResult.Ok)?.config?.ha != null }.distinctUntilChanged()
            combine(layoutStore.layout, apps, haSetUp) { stored, installed, ha -> Triple(stored, installed, ha) }.collect { (stored, installed, ha) ->
                if (installed.isEmpty()) return@collect
                val byLabel = installed.map { it.packageName }
                val saved = stored.layout
                if (saved == null) {
                    layoutStore.save(stored.legacyFavorites?.let { AppOrder.upgrade(it, byLabel) } ?: AppOrder.defaults(byLabel, haConfigured = ha))
                } else {
                    val reconciled = AppOrder.reconcile(saved, byLabel).let { if (ha) AppOrder.promoteHome(it) else it }
                    if (reconciled != saved) layoutStore.save(reconciled)
                }
            }
        }
        // "Next wallpaper" from the Control Center (either host): advance now. While an app is in front this just moves
        // the playlist on, so the next return Home shows it.
        // A timer, if any, then counts a full period from this change.
        viewModelScope.launch { core.nextWallpaperRequests.collect { nextWallpaper(); restartRotation() } }
        // Launcher settings changed (or first read from storage): new order → playlist rebuilt keeping the current
        // video; new interval → the timer restarts with it.
        viewModelScope.launch {
            core.settings.prefs.map { it.wallpaperOrder }.distinctUntilChanged().drop(1).collect {
                if (playlist != null) refreshWallpaper(advance = false)
            }
        }
        viewModelScope.launch {
            core.settings.prefs.map { it.wallpaperEveryMin }.distinctUntilChanged().drop(1).collect { restartRotation() }
        }
        // Uninstalled apps are only left out of the tray (see `trayApps`), not deleted from storage: during an app
        // update the package briefly disappears, and a reinstalled app should come back where it was.
    }

    /**
     * Called every time Home comes back to the front after an app: pick up config changes and newly pushed
     * videos. Any next wallpaper was already chosen in [onLeftHome], so its poster is loaded by now.
     */
    fun onReturnedHome() {
        core.applyConfig()
        refreshWallpaper(advance = false)
        makeMissingPosters()
        homeVisible = true
        restartRotation()
    }

    /**
     * Home went fully out of sight (an app opened, screen off). "New wallpaper on return Home" On: choose the next
     * wallpaper now; Off: keep the current one. Any timer stops and restarts a full period when Home is back.
     */
    fun onLeftHome() {
        posterJob?.cancel() // never use the decoder while an app is starting
        homeVisible = false
        rotation.stop()
        refreshWallpaper(advance = advanceOnLeave(core.settings.prefs.value))
    }

    /** While Home is showing, make posters for videos that don't have one yet, one at a time. */
    private fun makeMissingPosters() {
        posterJob?.cancel()
        posterJob = viewModelScope.launch {
            delay(5_000) // let the wallpaper start smoothly first
            withContext(Dispatchers.IO) {
                val videos = listWallpapers(wallpaperDir)
                posters.prune(videos)
                for (v in videos) {
                    if (!posters.has(v) && posters.generate(v)) Log.i(TAG, "poster made: ${v.name}")
                }
            }
            // The current wallpaper may have just got its poster.
            if (_poster.value == null) _wallpaper.value?.let { f -> _poster.value = withContext(Dispatchers.IO) { posters.load(File(f)) } }
        }
    }

    /**
     * Rescans the wallpaper folder. A changed folder restarts the playlist; a changed order (Launcher settings
     * override config.json) rebuilds it on the video playing now. Otherwise [advance] moves to the next video
     * (coming back from an app) or keeps the current one (Home pressed while home).
     */
    private fun refreshWallpaper(advance: Boolean) {
        val cfg = (configStore.state.value as? ConfigResult.Ok)?.config
        wallpaperDir.mkdirs() // (re)create it ourselves: a folder made by adb would be unreadable to us
        val files = listWallpapers(wallpaperDir).map { it.absolutePath }
        val order = core.settings.prefs.value.effectiveOrder(cfg?.wallpaperOrder ?: WallpaperOrder.SHUFFLE)
        val key = files to order
        val current = playlist
        _wallpaper.value = when {
            current == null || key != playlistKey -> {
                val onlyOrderChanged = current != null && playlistKey?.first == files
                playlistKey = key
                val keep = if (onlyOrderChanged) _wallpaper.value else null
                WallpaperPlaylist(key.first, key.second, startAt = keep).also { playlist = it }.current()
            }
            advance -> current.advance()
            else -> current.current()
        }
        Log.i(TAG, "wallpaper: ${_wallpaper.value?.let { File(it).name }} (advance=$advance) ha=${core.haConfigured}")
    }

    /** Skip to the next video in the playlist (Control Center's "Next wallpaper"). */
    fun nextWallpaper() = refreshWallpaper(advance = true)

    fun onWallpaperError(file: String) {
        _wallpaper.value = playlist?.markBroken(file)
        Log.w(TAG, "wallpaper can't play, skipping: ${File(file).name} → ${_wallpaper.value?.let { File(it).name }}")
    }

    fun launch(app: AppInfo) = appRepo.launch(app)
    fun openAppInfo(app: AppInfo) = appRepo.openAppInfo(app)
    fun banner(app: AppInfo) = appRepo.bannerBitmap(app)
    fun icon(app: AppInfo) = appRepo.iconBitmap(app)

    // Home pressed while already on the home screen: close overlays (UI watches this) + pick up config changes.
    private val _homePresses = MutableStateFlow(0)
    val homePresses: StateFlow<Int> = _homePresses.asStateFlow()
    fun onHomePressed() {
        core.applyConfig()
        refreshWallpaper(advance = false) // picks up freshly pushed videos
        _homePresses.value++
    }

    private fun installedPkgs(): Set<String> = apps.value.map { it.packageName }.toSet()

    /** Applies [change] on top of the latest layout (pending edits included) and saves it. */
    private fun edit(change: (AppLayout) -> AppLayout) {
        // pendingLayout first: `layout` (a stateIn of it) can lag a dispatch behind, and a quick second press would
        // then start from the layout before the first one.
        val current = pendingLayout.value ?: layout.value ?: return
        val next = change(current)
        if (next == current) return
        pendingLayout.value = next
        viewModelScope.launch {
            layoutStore.save(next)
            // Once storage has caught up with the latest edit, drop the overlay.
            if (pendingLayout.value == next) pendingLayout.value = null
        }
    }

    override fun onCleared() {
        appRepo.close() // HA lives in NemuiCore and outlives this view model
    }

    /** Move in the tray by [delta] visible slots. */
    fun moveApp(pkg: String, delta: Int) = edit { AppOrder.move(it, installedPkgs(), pkg, delta) }
    /** Move within the dock (first six). Only for apps currently in the dock. */
    fun moveInDock(pkg: String, delta: Int) = edit { AppOrder.moveInDock(it, installedPkgs(), pkg, delta) }
    fun hideApp(pkg: String) = edit { AppOrder.hide(it, pkg) }
    fun unhideApp(pkg: String) = edit { AppOrder.unhide(it, pkg) }
    fun uninstall(app: AppInfo) = appRepo.uninstall(app)

    val versionName: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
}

/** Stand-in for the ⌂ tile in the tray / dock lists; the UI draws it as a HomeTile, never as an app. */
val HOME_APP = AppInfo(AppOrder.HOME_ID, "Home", "", isLeanback = false, isSystem = true)

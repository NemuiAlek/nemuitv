package com.nemuigames.nemuitv.core

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nemuigames.nemuitv.NemuiApp
import com.nemuigames.nemuitv.alerts.AlertCenter
import com.nemuigames.nemuitv.alerts.MediaPauser
import com.nemuigames.nemuitv.camera.CameraBank
import com.nemuigames.nemuitv.config.AlertsConfig
import com.nemuigames.nemuitv.camera.WebRtcSessionRunner
import com.nemuigames.nemuitv.config.ConfigStore
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.config.HaConfig
import com.nemuigames.nemuitv.config.HubConfig
import com.nemuigames.nemuitv.config.LofiConfig
import com.nemuigames.nemuitv.config.SpotifyConfig
import com.nemuigames.nemuitv.config.TvConfig
import com.nemuigames.nemuitv.config.effectiveHa
import com.nemuigames.nemuitv.ha.HaClient
import com.nemuigames.nemuitv.ha.HaPresence
import com.nemuigames.nemuitv.hub.HubState
import com.nemuigames.nemuitv.ambient.Lofi
import com.nemuigames.nemuitv.ambient.SystemTimers
import com.nemuigames.nemuitv.ambient.accountPlaying
import com.nemuigames.nemuitv.ambient.mediaMayBePlaying
import com.nemuigames.nemuitv.ambient.shouldStartLofi
import com.nemuigames.nemuitv.ambient.tvShowsThisBox
import com.nemuigames.nemuitv.ambient.TimerWrite
import com.nemuigames.nemuitv.nowplaying.NowPlaying
import com.nemuigames.nemuitv.nowplaying.SPOTIFY_PKG
import com.nemuigames.nemuitv.settings.LauncherSettings
import com.nemuigames.nemuitv.spotify.PrefsTokenStore
import com.nemuigames.nemuitv.spotify.SpotifyLibrary
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * App-wide state shared by the Home activity and the Home-button overlay (same process): config, the HA client and
 * Now Playing and the Launcher settings.
 * Lives as long as the process; HA connects once at app start.
 */
class NemuiCore(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val filesDir: File = app.getExternalFilesDir(null) ?: app.filesDir
    val configStore = ConfigStore(filesDir)
    /** Where the video wallpapers live (same folder the home screen plays from). */
    val wallpaperDir: File = File(filesDir, "wallpapers")
    val ha = HaClient(scope, HaClient.defaultHttpClient())

    private var lastGoodTv: TvConfig? = null
    /** The TV setup in use (last good one from config.json), or null = "TV not set up". */
    val tvConfig: TvConfig? get() = lastGoodTv
    val tv = TvControl(ha, { lastGoodTv }, scope)

    private var lastGoodLofi: LofiConfig? = null
    /** The lofi setup in use (last good one from config.json), or null = "Lofi not set up". */
    val lofiConfig: LofiConfig? get() = lastGoodLofi

    private var lastGoodHa: HaConfig? = null

    @Volatile private var lastGoodSpotify: SpotifyConfig? = null

    private var lastGoodHub: HubConfig = HubConfig()
    /** The ⌂ hub's tweaks (people names, car colors) in use: the last good ones from config.json, else defaults. */
    val hubConfig: HubConfig get() = lastGoodHub

    private var lastGoodAlerts: AlertsConfig = AlertsConfig()
    /** Alert banner text overrides in use: the last good ones from config.json, else defaults. */
    val alertsConfig: AlertsConfig get() = lastGoodAlerts

    /** Whether HA is currently pointed at a server (for logging). */
    val haConfigured: Boolean get() = lastGoodHa != null

    /** Re-read config.json if it changed and point the HA client at the right server (keeping the last good one). */
    fun applyConfig() {
        configStore.loadIfChanged()
        lastGoodHa = effectiveHa(configStore.state.value, lastGoodHa)
        // Like HA: a broken edit keeps the last good TV setup; a missing file clears it.
        lastGoodTv = when (val r = configStore.state.value) {
            is ConfigResult.Ok -> r.config.tv
            is ConfigResult.Invalid -> lastGoodTv
            ConfigResult.Missing -> null
        }
        lastGoodLofi = when (val r = configStore.state.value) {
            is ConfigResult.Ok -> r.config.lofi
            is ConfigResult.Invalid -> lastGoodLofi
            ConfigResult.Missing -> null
        }
        lastGoodSpotify = when (val r = configStore.state.value) {
            is ConfigResult.Ok -> r.config.spotify
            is ConfigResult.Invalid -> lastGoodSpotify
            ConfigResult.Missing -> null
        }
        lastGoodHub = when (val r = configStore.state.value) {
            is ConfigResult.Ok -> r.config.hub
            is ConfigResult.Invalid -> lastGoodHub
            ConfigResult.Missing -> HubConfig()
        }
        lastGoodAlerts = when (val r = configStore.state.value) {
            is ConfigResult.Ok -> r.config.alerts
            is ConfigResult.Invalid -> lastGoodAlerts
            ConfigResult.Missing -> AlertsConfig()
        }
        ha.start(lastGoodHa, lastGoodTv?.entity, lastGoodLofi?.spotifyEntity)
    }

    /** The ⌂ Home hub's state (one per process; [scope] is Main, as HubState requires). Opened/closed by HubScreen. */
    val hub = HubState(ha, { lastGoodHub }, scope)

    /**
     * The hub's live cameras (one per process, Main scope). Created on first use; WebRTC's factory and EGL only when
     * the first session / renderer needs them. The hub drives it with setVisible(); nothing else starts sessions.
     */
    private val camerasLazy = lazy {
        CameraBank(scope, SystemClock::elapsedRealtime, System::currentTimeMillis) { bank ->
            WebRtcSessionRunner(ha, { bank.peerConnectionFactory(app) }, bank::sinksFor, bank::onVideoSize)
        }
    }
    val cameras: CameraBank by camerasLazy

    /** Stops every camera feed at once, without creating the bank (and its native code) if it was never used. */
    fun stopCamerasNow() { if (camerasLazy.isInitialized()) cameras.stopNow() }

    private val _nextWallpaperRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** "Next wallpaper" pressed in the Control Center; the home screen advances its playlist (shown now, or on return). */
    val nextWallpaperRequests: SharedFlow<Unit> = _nextWallpaperRequests.asSharedFlow()
    fun requestNextWallpaper() { _nextWallpaperRequests.tryEmit(Unit) }

    /** What's playing on the TV (any app); null = nothing / no permission. Runs while apps are in front too. */
    val nowPlaying = NowPlaying(app, scope)

    /** The ♥ in the Control Center player (Spotify Web API; hidden unless config.json has a `spotify` block). */
    val spotify = SpotifyLibrary({ lastGoodSpotify }, PrefsTokenStore(app), lazy { OkHttpClient() }, scope, Dispatchers.IO)

    /** Launcher settings page values (wallpaper order/timer, clock, weather unit); both panel hosts see the same. */
    val settings = LauncherSettings(app, scope)

    private val timers = SystemTimers(app)
    private val _timerWrite = MutableStateFlow(TimerWrite.OK)
    /** Whether Android's screensaver/sleep timers could be written (NO_PERMISSION until scripts/setup.sh has run). */
    val timerWrite: StateFlow<TimerWrite> = _timerWrite.asStateFlow()

    /** Re-writes Android's timers from the saved values (once loaded) and refreshes [timerWrite]; cheap and idempotent. */
    fun reapplyTimers() {
        if (settings.loaded.value) _timerWrite.value = timers.apply(settings.prefs.value)
    }

    /** The wallpaper the home screen is showing (absolute path), so the screensaver starts on the same video. */
    val currentWallpaper = MutableStateFlow<String?>(null)
    /** [currentWallpaper] when Home last paused (before its onStop can advance the playlist). Set by HomeActivity. */
    @Volatile var wallpaperAtHomePause: String? = null

    /** Home activity resumed right now; and when it last paused (elapsedRealtime). Set by HomeActivity. */
    @Volatile var homeResumed: Boolean = false
    @Volatile var homePausedAtMs: Long? = null

    private val _dreaming = MutableStateFlow(false)
    /** True while NemuiTV's screensaver ([com.nemuigames.nemuitv.ambient.AmbientDream]) is running. */
    val dreaming: StateFlow<Boolean> = _dreaming.asStateFlow()
    fun setDreaming(on: Boolean) { _dreaming.value = on }

    /** When the screensaver last began to end (elapsedRealtime; its exit fade start), for Home's black cover. */
    @Volatile var dreamEndedAtMs: Long? = null

    private val _homeStarted = MutableStateFlow(false)
    /** Home activity is started (visible). The screensaver's wallpaper waits for it to stop (decoder freed). */
    val homeStarted: StateFlow<Boolean> = _homeStarted.asStateFlow()
    fun setHomeStarted(on: Boolean) { _homeStarted.value = on }

    private val _dreamExitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Home pressed during the screensaver: the running AmbientDream finishes (nothing listens when none runs). */
    val dreamExitRequests: SharedFlow<Unit> = _dreamExitRequests.asSharedFlow()
    fun requestDreamExit() { _dreamExitRequests.tryEmit(Unit) }

    private val lofi = Lofi(ha::callService, scope, { lastGoodLofi }, { nowPlaying.isPlaying(SPOTIFY_PKG) }, dreaming)

    /**
     * Doorbell pop-up + banners (one per process, Main scope). Runs from app start whatever is on screen. The camera
     * lambdas touch [cameras] only when a doorbell with a camera actually rings, so the lazy bank isn't created early.
     */
    val alerts = AlertCenter(
        ha, { lastGoodAlerts }, scope, System::currentTimeMillis,
        { tvShowsThisBox(lastGoodTv?.entity, lastGoodTv?.thisDevice, ha.tvState.value) },
        MediaPauser { nowPlaying.playingHandle() },
        { cameras.acquireAlertFeed(it) }, { cameras.releaseAlertFeed(it) },
        { lastGoodHub.excludedAreas },
    )

    /**
     * The alert overlay window is attached (HomeTakeoverService running). While false, HomeActivity draws the alert
     * stack itself (in-app fallback); never both.
     */
    val alertOverlayOn = MutableStateFlow(false)

    /** NemuiTV's home screen (not the hub) is in front, so the alert stack starts below its top row. Set by HomeScreen. */
    val homeTopRowInFront = MutableStateFlow(false)

    /** The accessibility-overlay Control Center (hold Home) is on screen; home's [⊜] and banner hide meanwhile. */
    val ccOverlayShowing = MutableStateFlow(false)

    /** Home's own Control Center panel or media player is open (set by HomeScreen). */
    val homePanelOpen = MutableStateFlow(false)

    /** The Home-button service is connected (it can show the Control Center over apps). */
    val takeoverRunning = MutableStateFlow(false)

    private val _setupRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Open the Setup page in the service's over-apps Control Center (the home screen asks on first launch). */
    val setupOverlayRequests: SharedFlow<Unit> = _setupRequests.asSharedFlow()
    fun requestSetupOverlay() { _setupRequests.tryEmit(Unit) }

    /** Some Control Center (or home's player) is open: the alert banners step aside until it closes. */
    val ccOpen: StateFlow<Boolean> = combine(ccOverlayShowing, homePanelOpen) { a, b -> a || b }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** A new doorbell ring ([ringClosesCc]): every Control Center closes so the pop-up shows. */
    private val _closeCc = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeCcRequests: SharedFlow<Unit> = _closeCc.asSharedFlow()

    /** The screensaver just started; [wasHome] = the home screen was in front. Starts lofi when the rules allow. */
    fun onAmbientStarted(wasHome: Boolean) {
        val tvOnBox = tvShowsThisBox(lastGoodTv?.entity, lastGoodTv?.thisDevice, ha.tvState.value)
        val account = accountPlaying(ha.spotifyState.value)
        Log.i("NemuiTV", "ambient: started wasHome=$wasHome tvOnThisBox=$tvOnBox accountPlaying=$account " +
            "nowPlayingListening=${nowPlaying.isListening}")
        val mediaPlaying = mediaMayBePlaying(nowPlaying.isListening, nowPlaying.anyPlaying)
        val lofiReady = lastGoodLofi != null && HaPresence.showsHaUi(ha.status.value) // lofi plays through HA
        if (shouldStartLofi(wasHome, settings.prefs.value.lofiOn, mediaPlaying, lofiReady, tvOnBox, account)) {
            lofi.start()
        }
    }

    init {
        applyConfig()
        alerts.start()
        // TV turning off closes the doorbell (no resume) and clears banners; only on the true -> false edge.
        ha.tvState.map { tvShowsThisBox(lastGoodTv?.entity, lastGoodTv?.thisDevice, it) }
            .distinctUntilChanged()
            .onEach { shows -> if (!shows) alerts.onTvOff() }
            .launchIn(scope)
        nowPlaying.start()
        var lastDoorbell: com.nemuigames.nemuitv.alerts.DoorbellUi? = null
        alerts.ui.onEach { ui ->
            if (com.nemuigames.nemuitv.alerts.ringClosesCc(lastDoorbell, ui.doorbell)) _closeCc.tryEmit(Unit)
            lastDoorbell = ui.doorbell
        }.launchIn(scope)
        // Write Android's timers whenever the saved values change (first run after DataStore is read, never the
        // defaults before it). Also re-applied on every Home resume via reapplyTimers().
        settings.loaded.filter { it }.onEach { _ ->
            settings.prefs.map { Triple(it.ambientAfterMin, it.blackAfterMin, it.sleepAfterMin) }.distinctUntilChanged()
                .onEach { _timerWrite.value = timers.apply(settings.prefs.value) }
                .launchIn(scope)
        }.launchIn(scope)
    }
}


val Context.core: NemuiCore get() = (applicationContext as NemuiApp).core

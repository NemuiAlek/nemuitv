package com.nemuigames.nemuitv.home

import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.LinearEasing
import com.nemuigames.nemuitv.controlcenter.PlayerMorph
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.apps.AppInfo
import com.nemuigames.nemuitv.controlcenter.CcPage
import com.nemuigames.nemuitv.controlcenter.ControlCenter
import com.nemuigames.nemuitv.controlcenter.ControlCenterState
import com.nemuigames.nemuitv.setup.SetupStore
import kotlinx.coroutines.flow.first
import com.nemuigames.nemuitv.controlcenter.launchPackage
import com.nemuigames.nemuitv.ambient.HOME_COVER_FADE_MS
import com.nemuigames.nemuitv.ambient.homeCoverLifts
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.controlcenter.MediaButton
import com.nemuigames.nemuitv.controlcenter.MediaPlayer
import com.nemuigames.nemuitv.nowplaying.NowPlayingState
import com.nemuigames.nemuitv.nowplaying.PlayerGoneFocus
import com.nemuigames.nemuitv.nowplaying.playerGoneFocus
import com.nemuigames.nemuitv.favorites.AppOrder
import com.nemuigames.nemuitv.hub.HubScreen
import com.nemuigames.nemuitv.tray.AppMenu
import com.nemuigames.nemuitv.tray.AppMenuActions
import com.nemuigames.nemuitv.tray.AppTray
import com.nemuigames.nemuitv.tray.FocusedTile
import com.nemuigames.nemuitv.tray.HiddenAppsPanel
import com.nemuigames.nemuitv.tray.HiddenAppsTile
import com.nemuigames.nemuitv.tray.TrayEditState
import com.nemuigames.nemuitv.tray.TrayTopRoom
import com.nemuigames.nemuitv.tray.toDockKey
import com.nemuigames.nemuitv.tray.trayKey
import com.nemuigames.nemuitv.wallpaper.WallpaperLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.nemuigames.nemuitv.wallpaper.listWallpapers
import kotlinx.coroutines.flow.StateFlow

/** HIDDEN_APPS is the Hidden apps panel, shown over the (still open) tray. PLAYER is the media player in the banner's spot. */
enum class Overlay { NONE, TRAY, CONTROL_CENTER, HIDDEN_APPS, PLAYER }

/** requestTrayFocus(HIDDEN_TILE) focuses the Hidden apps tile. */
private const val HIDDEN_TILE = "__hidden"

/** Waits (a few frames at most) for [pkg] to show up in [list] — after a hide/move the new order arrives a frame later. */
private suspend fun awaitShown(pkg: String?, list: () -> List<AppInfo>) {
    if (pkg == null || pkg == HIDDEN_TILE) return
    repeat(10) { if (list().any { it.packageName == pkg }) return; withFrameNanos { } }
}

/** Runs [request] until it reports success, once per frame for a few frames (a tile may not be composed / focusable
 *  yet — e.g. the dock right after the tray closed, or a grid item that's being scrolled into view). */
private suspend fun retryFocus(request: suspend () -> Boolean) {
    repeat(10) { if (request()) return; withFrameNanos { } }
}

/** requestFocus that says whether it worked (the plain one fails silently, e.g. on a tile that isn't composed). */
private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/** The app grid's first row starts just below the clock/date block (the grid itself starts a little higher, so a
 *  lifted tile in row 1 has room to rise). */
private val TrayTop = 128.dp - (TrayTopRoom - 14.dp)

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val context = LocalContext.current
    // Plain collectAsState: the lifecycle-aware version stops updating below STARTED, so "stopped" would never arrive.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val stale by vm.weatherStale.collectAsStateWithLifecycle()
    val launcherPrefs by context.core.settings.prefs.collectAsStateWithLifecycle()
    val dock by vm.dock.collectAsStateWithLifecycle()
    val trayApps by vm.trayApps.collectAsStateWithLifecycle()
    val hiddenApps by vm.hiddenApps.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    // The ⌂ Home hub page: replaces the top bar + dock (the wallpaper, shading and clock stay). Not an Overlay: it's a
    // page of its own with its own Back handling (HubScreen).
    var hubOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<AppInfo?>(null) }
    // Dock edit mode (rules + tests in DockEdit.kt). `lifted` is the app being slid around.
    var edit by remember { mutableStateOf(DockEditState()) }
    var focusedDockPkg by remember { mutableStateOf<String?>(null) }
    // The app focused in the tray (null = ⌂ / Hidden apps tile). Updated by AppTray.
    val focusedTray = remember { FocusedTile() }
    // Same for the dock (kept next to focusedDockPkg, which is UI state): remembers a focused app that vanished.
    val focusedDock = remember { FocusedTile() }
    fun dockFocused(pkg: String?) { focusedDockPkg = pkg; focusedDock.focusedApp(pkg) }
    val movingPkg = edit.lifted
    // Tray edit mode (rules + tests in tray/TrayEdit.kt): the whole grid, ◀ ▶ ▲ ▼.
    var trayEdit by remember { mutableStateOf(TrayEditState()) }
    // Where focus goes when the long-press menu closes, if an action decided it (e.g. after Hide). Not UI state.
    val afterMenuFocus = remember { arrayOfNulls<String>(1) }

    val controlCenterFocus = remember { FocusRequester() }
    val bannerFocus = remember { FocusRequester() }
    val favFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun favoriteFocus(pkg: String) = favFocus.getOrPut(pkg) { FocusRequester() }
    val trayFocusMap = remember { mutableStateMapOf<String, FocusRequester>() }
    fun trayFocus(pkg: String) = trayFocusMap.getOrPut(pkg) { FocusRequester() }
    val hiddenTileFocus = remember { FocusRequester() }
    fun focusDock(prefer: String? = null): Boolean {
        val target = movingPkg ?: prefer?.takeIf { p -> dock.any { it.packageName == p } } ?: AppOrder.defaultFocus(dock.map { it.packageName })
        return target != null && favoriteFocus(target).tryFocus()
    }

    // Ask for dock focus on the next frame, after an overlay (and its focus trap) has gone away.
    var dockFocusRequests by remember { mutableStateOf(0) }
    var dockFocusPrefer by remember { mutableStateOf<String?>(null) }
    fun requestDockFocus(prefer: String? = null) { dockFocusPrefer = prefer; dockFocusRequests++ }
    LaunchedEffect(dockFocusRequests) {
        if (dockFocusRequests > 0) {
            withFrameNanos { }
            awaitShown(dockFocusPrefer) { dock }
            retryFocus { focusDock(dockFocusPrefer) } // the dock can't take focus until it has recomposed as focusable
        }
    }

    // The tray grid's scroll state, here so a tile can be scrolled into view before it's focused (a LazyGrid only
    // composes the items on screen; asking an off-screen one for focus does nothing). A fresh one each time the tray
    // appears, so it always opens at the top (as before).
    val trayOpen = overlay == Overlay.TRAY || overlay == Overlay.HIDDEN_APPS
    // 0 = dock showing, 1 = app grid showing. Down: the dock rises + fades out while the grid rises + fades in
    // below the clock (the clock stays). ModulateAlpha fades each draw instead of an offscreen layer (cheaper GPU).
    val trayProgress by animateFloatAsState(if (trayOpen) 1f else 0f, tween(if (trayOpen) 260 else 220), label = "tray")
    val trayMounted = trayOpen || trayProgress > 0f
    val trayGrid = remember(trayMounted) { LazyGridState() }

    // Same for a tray tile (HIDDEN_TILE = the Hidden apps tile; null or unknown = the first one).
    var trayFocusRequests by remember { mutableStateOf(0) }
    var trayFocusPrefer by remember { mutableStateOf<String?>(null) }
    fun requestTrayFocus(prefer: String?) { trayFocusPrefer = prefer; trayFocusRequests++ }
    LaunchedEffect(trayFocusRequests) {
        if (trayFocusRequests == 0) return@LaunchedEffect
        val prefer = trayFocusPrefer
        withFrameNanos { }
        awaitShown(prefer) { trayApps }
        retryFocus {
            val appIndex = trayApps.indexOfFirst { it.packageName == prefer }
            val (gridIndex, requester) = when {
                prefer == HIDDEN_TILE -> trayApps.size to hiddenTileFocus
                appIndex >= 0 -> appIndex to trayFocus(prefer!!)
                trayApps.isNotEmpty() -> 0 to trayFocus(trayApps.first().packageName)
                else -> trayApps.size to hiddenTileFocus
            }
            if (trayGrid.layoutInfo.visibleItemsInfo.none { it.index == gridIndex }) runCatching { trayGrid.scrollToItem(gridIndex) }
            requester.tryFocus()
        }
    }

    // Back to [⊜] once the Control Center (and its focus trap) has left the screen.
    var ccButtonFocusRequests by remember { mutableStateOf(0) }
    LaunchedEffect(ccButtonFocusRequests) {
        if (ccButtonFocusRequests > 0) { withFrameNanos { }; retryFocus { controlCenterFocus.tryFocus() } }
    }

    // Back to the banner once the player (and its focus trap) has left the screen.
    var bannerFocusRequests by remember { mutableStateOf(0) }
    LaunchedEffect(bannerFocusRequests) {
        if (bannerFocusRequests > 0) {
            withFrameNanos { }
            // Banner gone (session ended meanwhile): [⊜] instead.
            retryFocus { bannerFocus.tryFocus() }
            if (!bannerFocus.tryFocus()) controlCenterFocus.tryFocus()
        }
    }

    fun closeOverlay(preferDock: String? = null) {
        val was = overlay
        overlay = Overlay.NONE
        when (was) {
            Overlay.CONTROL_CENTER -> ccButtonFocusRequests++
            Overlay.PLAYER -> bannerFocusRequests++
            Overlay.TRAY -> requestDockFocus(preferDock)
            Overlay.HIDDEN_APPS -> { overlay = Overlay.TRAY; requestTrayFocus(HIDDEN_TILE) }
            Overlay.NONE -> Unit
        }
    }

    /** Where focus should land after [pkg] is hidden: its neighbour in the new order (AppOrder.neighbour), or the first entry (null). */
    fun neighbourAfterHiding(pkg: String, inTray: Boolean): String? {
        val before = (if (inTray) trayApps else dock).map { it.packageName }
        val after = trayApps.map { it.packageName }.filter { it != pkg }.let { if (inTray) it else AppOrder.favorites(it) }
        return AppOrder.neighbour(pkg, before, after)
    }

    val actions = remember(vm) {
        object : AppMenuActions {
            override fun startMove(pkg: String) {
                if (overlay != Overlay.TRAY && dock.any { it.packageName == pkg }) {
                    edit = DockEditState.startEditing(pkg) // dock edit: ◀ ▶ within the favorites
                } else {
                    edit = DockEditState.stopEditing()
                    overlay = Overlay.TRAY
                    trayEdit = TrayEditState.startEditing(pkg)
                }
            }
            override fun hide(pkg: String) {
                afterMenuFocus[0] = neighbourAfterHiding(pkg, inTray = overlay == Overlay.TRAY)
                vm.hideApp(pkg)
            }
            override fun uninstall(app: AppInfo) = vm.uninstall(app)
            override fun forceClose(app: AppInfo) = vm.openAppInfo(app)
        }
    }

    BackHandler(enabled = overlay != Overlay.NONE) { closeOverlay() }

    // Home pressed while already home: close everything, back to the dock.
    val homePresses by vm.homePresses.collectAsStateWithLifecycle()
    LaunchedEffect(homePresses) {
        if (homePresses > 0) {
            menuFor = null; edit = DockEditState.stopEditing(); trayEdit = TrayEditState.stopEditing(); overlay = Overlay.NONE
            hubOpen = false
            requestDockFocus()
        }
    }
    BackHandler(enabled = edit.editing) { edit = DockEditState.stopEditing() }
    // Registered after the overlay's handler, so Back while tray editing only ends edit mode (the tray stays open).
    BackHandler(enabled = trayEdit.editing) { trayEdit = TrayEditState.stopEditing() }

    // Banner ⇄ player morph (PlayerMorph): the player grows out of the banner and folds back into it. Not when the
    // service's overlay takes over (it draws its own banner): the home player just goes, so nothing is drawn twice.
    val playerMorph = remember { Animatable(0f) }
    val playerOpen = overlay == Overlay.PLAYER
    LaunchedEffect(playerOpen) {
        if (!playerOpen && context.core.ccOverlayShowing.value) playerMorph.snapTo(0f)
        else playerMorph.animateTo(if (playerOpen) 1f else 0f,
            tween(if (playerOpen) PlayerMorph.OPEN_MS else PlayerMorph.CLOSE_MS, easing = LinearEasing))
    }
    val playerDrawn by remember { derivedStateOf { overlay == Overlay.PLAYER || playerMorph.value > 0f } }

    // Music gone while the home player is open: close it, focus [⊜] (playerGoneFocus(overApp = false)). Watched in the
    // effect, not collected here, so track / play-pause changes don't recompose the whole home screen.
    LaunchedEffect(overlay) {
        if (overlay != Overlay.PLAYER) return@LaunchedEffect
        context.core.nowPlaying.current.first { it == null }
        overlay = Overlay.NONE
        if (playerGoneFocus(overApp = false) == PlayerGoneFocus.CC_BUTTON) ccButtonFocusRequests++
    }

    // Hold Home on the home screen opens the service's Control Center overlay (the service eats the Home key, so
    // homePresses never fires). Nothing is drawn twice: close the in-app panel / player under it (no focus request:
    // the overlay has the keys). When it leaves, give focus back if home has none (the banner / [⊜] were hidden under
    // it, so focus may have been cleared): the banner, else [⊜].
    val homeFocused = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        var wasShowing = false
        context.core.ccOverlayShowing.collect { showing ->
            if (showing) {
                if (overlay == Overlay.PLAYER || overlay == Overlay.CONTROL_CENTER) overlay = Overlay.NONE
            } else if (wasShowing && !homeFocused[0] && !hubOpen) {
                bannerFocusRequests++
            }
            wasShowing = showing
        }
    }

    // Start on the first favorite (or ⌂ while favorites are still loading).
    LaunchedEffect(dock.isNotEmpty()) { if (overlay == Overlay.NONE) focusDock() }
    // While moving, keep focus on the tile being moved as it changes position.
    LaunchedEffect(movingPkg, dock) { if (movingPkg != null) focusDock() }
    // An app went away (uninstalled / updating / hidden / pushed out of the dock). If it was lifted, editing stops;
    // if it had focus, focus moves to its neighbour (same index, else previous, else ⌂) in whatever is showing.
    val lastTrayOrder = remember { arrayOf(emptyList<String>()) }
    LaunchedEffect(trayApps) {
        val before = lastTrayOrder[0]
        val now = trayApps.map { it.packageName }
        lastTrayOrder[0] = now
        val dockNow = AppOrder.favorites(now)
        trayEdit.lifted?.let { if (it !in now) trayEdit = TrayEditState.stopEditing() }
        edit.lifted?.let { if (it !in dockNow) edit = DockEditState.stopEditing() }
        if (menuFor != null) return@LaunchedEffect // the menu has focus; it decides where focus goes when it closes
        when (overlay) {
            Overlay.TRAY -> (focusedTray.pkg ?: focusedTray.vanished)?.takeIf { it !in now }?.let { gone ->
                focusedTray.vanished = null
                requestTrayFocus(AppOrder.neighbour(gone, before, now))
            }
            Overlay.NONE -> (focusedDockPkg ?: focusedDock.vanished)?.takeIf { it !in dockNow }?.let { gone ->
                focusedDock.vanished = null
                requestDockFocus(AppOrder.neighbour(gone, AppOrder.favorites(before), dockNow))
            }
            else -> Unit // the Hidden apps panel / Control Center has focus
        }
    }

    val editing = edit.editing || trayEdit.editing
    focusedDock.apps = dock // read when a dock tile is removed (see FocusedTile.vanished)

    val tile: @Composable (AppInfo, Modifier) -> Unit = { app, mod ->
        if (app.packageName == AppOrder.HOME_ID) HomeTile(
            onClick = { if (!editing) { if (overlay == Overlay.TRAY) overlay = Overlay.NONE; hubOpen = true } },
            onLongClick = { if (!editing) menuFor = app },
            modifier = mod,
            lifted = app.packageName == (if (trayOpen) trayEdit.lifted else edit.lifted),
        ) else AppTile(
            app, vm.banner(app), vm.icon(app),
            onClick = { if (!editing) { vm.launch(app); if (overlay == Overlay.TRAY) closeOverlay() } },
            onLongClick = { if (!editing) menuFor = app },
            modifier = mod,
            lifted = app.packageName == (if (trayOpen) trayEdit.lifted else edit.lifted),
        )
    }


    val wallpaperPoster by vm.wallpaperPoster.collectAsStateWithLifecycle()
    // Back from the screensaver: hold the video (poster only) while the dream is still fading out on top, so the two
    // wallpaper players never hold a decoder at once.
    val dreaming by context.core.dreaming.collectAsState()
    // The alert stack (overlay or in-app fallback) starts below the top row (Now Playing + [⊜]) while it's on screen:
    // home visible and not the hub (which has no top row).
    val topRowInFront = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !hubOpen
    LaunchedEffect(topRowInFront) { context.core.homeTopRowInFront.value = topRowInFront }
    DisposableEffect(Unit) { onDispose { context.core.homeTopRowInFront.value = false } }
    // The alert banners step aside while home's Control Center panel or player is open; a doorbell ring closes them.
    val homePanelOpen = overlay == Overlay.CONTROL_CENTER || overlay == Overlay.PLAYER
    LaunchedEffect(homePanelOpen) { context.core.homePanelOpen.value = homePanelOpen }
    DisposableEffect(Unit) { onDispose { context.core.homePanelOpen.value = false } }
    LaunchedEffect(Unit) {
        context.core.closeCcRequests.collect {
            if (overlay == Overlay.CONTROL_CENTER || overlay == Overlay.PLAYER) closeOverlay()
        }
    }
    // First run: until "Done" on the Setup page, show it on launch. With the Home-button service running it opens in the
    // over-apps Control Center (like a held Home); without, in this screen's own panel.
    var startPage by remember { mutableStateOf<CcPage?>(null) }
    LaunchedEffect(Unit) {
        val store = SetupStore(context)
        if (store.checklistDone.first()) return@LaunchedEffect
        if (context.core.takeoverRunning.value) context.core.requestSetupOverlay()
        else { startPage = CcPage.SETUP; overlay = Overlay.CONTROL_CENTER }
    }
    // The updater asks to come back (permission, cancel or failure sent the user away or closed the panel): the
    // service overlay handles it when it runs, otherwise this screen's own panel opens on the Update page.
    LaunchedEffect(Unit) {
        context.core.update.reopenRequests.collect {
            // Already open: its page state is live, and a startPage set now would linger until the next open.
            if (context.core.takeoverRunning.value || overlay == Overlay.CONTROL_CENTER) return@collect
            startPage = CcPage.UPDATE; overlay = Overlay.CONTROL_CENTER
        }
    }
    // No-wallpaper hint (see WallpaperHint.kt). Recounted whenever Home comes back to the front (files are pushed from outside).
    val hintStore = remember { SetupStore(context) }
    val hintDismissed by hintStore.wallpaperHintDismissed.collectAsState(initial = true)
    val ccOverlayShowing by context.core.ccOverlayShowing.collectAsState()
    var wallpaperCount by remember { mutableStateOf(-1) }
    val homeStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(homeStarted) {
        if (homeStarted) wallpaperCount = withContext(Dispatchers.IO) { listWallpapers(context.core.wallpaperDir).size }
    }
    val hintShown = wallpaperCount >= 0 && !hubOpen && !trayOpen && !edit.editing && !homePanelOpen &&
        showWallpaperHint(wallpaperCount, hintDismissed, checklistOpen = overlay == Overlay.CONTROL_CENTER || ccOverlayShowing)
    val hintFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().onFocusChanged { homeFocused[0] = it.hasFocus }) {
        WallpaperLayer(
            file = wallpaper,
            poster = wallpaperPoster,
            // Keep playing until fully hidden.
            playing = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !(vm.homeCover && dreaming),
            onError = vm::onWallpaperError,
        )
        // Soft shading at top and bottom keeps text readable over bright videos.
        Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))

        // The hub dims the wallpaper a little more (mockup `.dim`), under the clock so the clock stays as bright as home.
        if (hubOpen) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(Modifier.fillMaxSize().padding(horizontal = Jt.SafeAreaH, vertical = Jt.SafeAreaV)) {
            Row(Modifier.fillMaxWidth()) {
                ClockWeather(weather, stale, clock = launcherPrefs.clock, unit = launcherPrefs.unit)
                Spacer(Modifier.weight(1f))
                if (!hubOpen) TopBar(
                    onControlCenter = { overlay = Overlay.CONTROL_CENTER },
                    controlCenterFocus = controlCenterFocus,
                    // Collected inside TopBar (lifecycle-aware): Now Playing updates then recompose only the banner,
                    // and nothing at all while an app is in front.
                    nowPlayingFlow = context.core.nowPlaying.current,
                    panelOpen = overlay == Overlay.CONTROL_CENTER,
                    overlayShowing = context.core.ccOverlayShowing,
                    playerOpen = overlay == Overlay.PLAYER,
                    bannerMorph = { playerMorph.value },
                    alertUi = context.core.alerts.ui,
                    bannerFocus = bannerFocus,
                    onOpenBanner = { overlay = Overlay.PLAYER }, // the player looks the ♥ up itself
                    // Focus up here: no dock app is focused any more (so one vanishing doesn't pull focus back down).
                    modifier = Modifier.padding(top = TopBarTopPadding).onFocusChanged { if (it.hasFocus) dockFocused(null) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (!hubOpen) {
                if (edit.editing) {
                    val hint = edit.lifted?.let { pkg ->
                        "Moving ${dock.firstOrNull { it.packageName == pkg }?.label ?: ""}  ·  ◀ ▶ to slide  ·  OK to drop"
                    } ?: "Editing favorites  ·  OK to pick an app up  ·  Back when done"
                    Text(hint, fontSize = 13.sp, color = Jt.TextPrimary, style = androidx.compose.ui.text.TextStyle(shadow = Jt.Legible),
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                Dock(
                    modifier = Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - trayProgress
                        translationY = -32.dp.toPx() * trayProgress
                    },
                    favorites = dock,
                    tile = tile,
                    onKey = { e ->
                        if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) return@Dock false
                        if (e.key == Key.DirectionUp && !edit.editing) { // up to the hint if showing, else the top row: banner if showing, else [⊜]
                            if (e.type == KeyEventType.KeyDown) {
                                if (hintShown && hintFocus.tryFocus()) return@Dock true
                                // Read at the press, not collected here (see TopBar).
                                val bannerShowing = context.core.nowPlaying.current.value != null
                                val target = when (upFromDockTarget(bannerShowing)) {
                                    TopBarTarget.BANNER -> bannerFocus
                                    TopBarTarget.CONTROL_CENTER -> controlCenterFocus
                                }
                                if (!target.tryFocus()) controlCenterFocus.tryFocus()
                            }
                            return@Dock true
                        }
                        val r = dockKey(edit, e.key.toDockKey(), down = e.type == KeyEventType.KeyDown, focusedPkg = focusedDockPkg)
                        edit = r.state
                        r.move?.let { d -> edit.lifted?.let { vm.moveInDock(it, d) } }
                        if (r.openTray) overlay = Overlay.TRAY
                        r.consumed
                    },
                    onFavoriteFocused = ::dockFocused,
                    favoriteFocus = ::favoriteFocus,
                    focusable = !trayOpen, // the tray covers it: ▲ / a lost focus must never land on the invisible dock
                )
            }
        }

        if (hintShown) {
            WallpaperHint(
                gotItFocus = hintFocus,
                onKey = { e ->
                    if (e.type != KeyEventType.KeyDown) return@WallpaperHint e.key == Key.DirectionUp || e.key == Key.DirectionDown
                    when (e.key) {
                        Key.DirectionDown -> { focusDock(); true }
                        Key.DirectionUp -> { if (!controlCenterFocus.tryFocus()) focusDock(); true }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                },
                onDismiss = { scope.launch { hintStore.setWallpaperHintDismissed(true) }; requestDockFocus() },
                // Above the dock (tile height + its vertical room), bottom-left on the safe margin.
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = Jt.SafeAreaH, bottom = Jt.SafeAreaV + TileHeight + 28.dp),
            )
        }

        if (hubOpen) {
            HubScreen(core = context.core, onExit = { hubOpen = false; requestDockFocus(AppOrder.HOME_ID) })
        }

        if (overlay == Overlay.CONTROL_CENTER) {
            // Handles its own keys (Back included: sub-page → main page → close).
            ControlCenter(
                core = context.core,
                onClose = { closeOverlay() },
                onOpenApp = { pkg -> closeOverlay(); context.launchPackage(pkg) },
                // Opened from [⊜] (focused): it cross-fades into the column, the banner stays put.
                overHomeTopRow = true, ccButtonFocused = true, focusOnClose = controlCenterFocus,
                state = remember { ControlCenterState().also { startPage?.let { page -> it.startAt(page); startPage = null } } },
            )
        }

        if (playerDrawn) {
            HomePlayer(
                core = context.core, nowPlayingFlow = context.core.nowPlaying.current,
                active = overlay == Overlay.PLAYER, morph = { playerMorph.value },
                onOpenApp = { pkg -> overlay = Overlay.NONE; context.launchPackage(pkg) },
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = Jt.SafeAreaV + TopBarTopPadding, end = Jt.SafeAreaH + 40.dp + 12.dp),
            )
        }

        if (trayOpen || trayProgress > 0f) {
            AppTray(
                apps = trayApps,
                editState = trayEdit,
                onKey = { key, down, repeatCount, pkg ->
                    val r = trayKey(trayEdit, key, down, repeatCount, pkg)
                    trayEdit = r.state
                    r.move?.let { d -> trayEdit.lifted?.let { vm.moveApp(it, d) } }
                    r.consumed
                },
                hiddenTile = { mod ->
                    HiddenAppsTile(onClick = { if (!trayEdit.editing) overlay = Overlay.HIDDEN_APPS }, modifier = mod.focusRequester(hiddenTileFocus))
                },
                tile = tile, focusFor = ::trayFocus,
                gridState = trayGrid, focused = focusedTray,
                initialFocusPkg = trayEdit.lifted ?: focusedDockPkg,
                onUpFromTopRow = { pkg -> closeOverlay(preferDock = pkg) },
                // Not while the long-press menu / Hidden apps panel is up: the trap would keep their focus request out.
                trapped = overlay == Overlay.TRAY && menuFor == null,
                modifier = Modifier
                    .padding(start = Jt.SafeAreaH - 10.dp, end = Jt.SafeAreaH - 10.dp, top = TrayTop, bottom = Jt.SafeAreaV)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = trayProgress
                        translationY = 40.dp.toPx() * (1f - trayProgress)
                    },
            )
        }

        if (trayEdit.editing) {
            val hint = trayEdit.lifted?.let { pkg ->
                "Moving ${trayApps.firstOrNull { it.packageName == pkg }?.label ?: ""}  ·  ◀ ▶ ▲ ▼ to move  ·  OK to drop"
            } ?: "Editing the tray  ·  OK to pick an app up  ·  Back when done"
            Text(hint, fontSize = 13.sp, color = Jt.TextPrimary, style = androidx.compose.ui.text.TextStyle(shadow = Jt.Legible),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }

        if (overlay == Overlay.HIDDEN_APPS) {
            HiddenAppsPanel(apps = hiddenApps, icon = vm::icon, onShow = vm::unhideApp, onDismiss = { closeOverlay() })
        }

        menuFor?.let { app ->
            AppMenu(app = app, actions = actions, onDismiss = {
                menuFor = null
                // Put focus back on the tile that was long-pressed, or where the action decided (after Hide);
                // nothing to do when Move started (the lifted tile takes focus).
                val target = afterMenuFocus[0] ?: app.packageName
                afterMenuFocus[0] = null
                when {
                    edit.lifted != null || trayEdit.lifted != null -> Unit
                    overlay == Overlay.TRAY -> requestTrayFocus(target)
                    else -> requestDockFocus(prefer = target)
                }
            })
        }

        HomeCover(vm, wallpaperReady = wallpaper == null || wallpaperPoster != null)
    }
}

/**
 * The [MediaPlayer] in the banner's spot, wired like the Control Center's. Takes focus on ⏯ and traps it. Looks the ♥ up
 * when it opens and when the track changes (only then: lookup resets it); clears it when it leaves.
 */
@Composable
private fun HomePlayer(
    core: com.nemuigames.nemuitv.core.NemuiCore,
    nowPlayingFlow: StateFlow<NowPlayingState?>,
    active: Boolean,
    morph: () -> Float,
    onOpenApp: (String) -> Unit,
    modifier: Modifier,
) {
    // Collected here so a track change recomposes only the player, not the home screen around it.
    val np by nowPlayingFlow.collectAsStateWithLifecycle()
    np?.let { cur -> HomePlayerContent(core, cur, active, morph, onOpenApp = { onOpenApp(cur.pkg) }, modifier) }
}

@Composable
private fun HomePlayerContent(
    core: com.nemuigames.nemuitv.core.NemuiCore, np: NowPlayingState, active: Boolean, morph: () -> Float,
    onOpenApp: () -> Unit, modifier: Modifier,
) {
    val player by core.nowPlaying.player.collectAsStateWithLifecycle()
    val heart by core.spotify.heart.collectAsStateWithLifecycle()
    val playPauseFocus = remember { FocusRequester() }
    LaunchedEffect(np.pkg, np.title) { core.spotify.lookup(np.pkg, np.title) }
    DisposableEffect(Unit) { onDispose { core.spotify.clear() } }
    LaunchedEffect(Unit) { withFrameNanos { }; retryFocus { playPauseFocus.tryFocus() } }
    MediaPlayer(
        np, player, heart,
        onButton = { b ->
            when (b) {
                MediaButton.PREVIOUS -> core.nowPlaying.previous()
                MediaButton.PLAY_PAUSE -> core.nowPlaying.playPause()
                MediaButton.NEXT -> core.nowPlaying.next()
            }
        },
        onShuffle = { core.nowPlaying.setShuffle(!(player?.shuffle ?: false)) },
        onRepeat = { core.nowPlaying.cycleRepeat() },
        onHeart = { core.spotify.toggle() },
        onOpenApp = onOpenApp,
        playPauseFocus = playPauseFocus,
        // Trapped while open; while it folds back into the banner, focus can't come back in.
        modifier = modifier.focusProperties { onEnter = { if (!active) cancelFocusChange() } }.trapFocus(active),
        morph = morph,
    )
}

/**
 * Black over all of Home after the screensaver (set in HomeActivity.onStart), so the switch from the screensaver's
 * wallpaper video to Home's is hidden: lifts ([HOME_COVER_FADE_MS]) once the dream is gone and the wallpaper is up,
 * 1 s at most ([homeCoverLifts]).
 */
@Composable
private fun HomeCover(vm: HomeViewModel, wallpaperReady: Boolean) {
    val core = LocalContext.current.core
    val on = vm.homeCover
    val alpha = remember { Animatable(1f) }
    var lifting by remember { mutableStateOf(false) }
    val ready by rememberUpdatedState(wallpaperReady)
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        var endedAt: Long? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val dreaming = core.dreaming.value
            endedAt = if (dreaming) null else endedAt ?: now
            if (homeCoverLifts(dreaming, ready, now - (endedAt ?: now))) break
            delay(50)
        }
        alpha.snapTo(1f)
        lifting = true
        try { alpha.animateTo(0f, tween(HOME_COVER_FADE_MS)) } finally { lifting = false; vm.coverLifted() }
    }
    // Full black from the first frame (before the effect runs), then the fade (read in the layer: no recompositions).
    if (on || lifting) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = if (lifting) alpha.value else 1f }.background(Color.Black))
    }
}

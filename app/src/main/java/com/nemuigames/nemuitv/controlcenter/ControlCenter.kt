package com.nemuigames.nemuitv.controlcenter

import androidx.compose.foundation.background
import com.nemuigames.nemuitv.home.ControlCenterGlyph
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.nemuigames.nemuitv.home.TopBarTopPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.config.WifiConfig
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.core.TvStatus
import com.nemuigames.nemuitv.home.DockKey
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.home.NowPlayingBanner
import com.nemuigames.nemuitv.nowplaying.NowPlayingState
import com.nemuigames.nemuitv.ha.inputsAvailable
import com.nemuigames.nemuitv.nowplaying.PlayerGoneFocus
import com.nemuigames.nemuitv.nowplaying.playerGoneFocus
import com.nemuigames.nemuitv.home.trapFocus
import com.nemuigames.nemuitv.tray.toDockKey

/** List rows (Inputs, Launcher settings). */
internal val CcRowShape = RoundedCornerShape(12.dp)
private val CcPanelShape = RoundedCornerShape(22.dp)
private val CcPanelWidth = 180.dp // sub-pages (Inputs, Launcher settings); was 330 (half the original size)
private val CcPanelPadding = 14.dp
private val CcCircleSize = 40.dp // same as the [⊜] button that opens the panel
private val CcIconSize = 18.dp
private val CcGap = 12.dp
/** The bare column's unfold (open) and fold (close) times. */
private const val CcFoldOpenMs = 220
private const val CcFoldCloseMs = 180

/**
 * Where the panel sits in its full-screen host (home screen and the over-apps overlay alike): the top-right safe-area
 * corner, covering the [⊜] button while open.
 */
internal val CcPanelPosition = Modifier.padding(top = Jt.SafeAreaV, end = Jt.SafeAreaH)

/** Which page the panel shows + where focus returns on the main page. Held by the host so its Back can drive it. */
class ControlCenterState {
    var page by mutableStateOf(CcPage.MAIN)
        internal set
    /** The tile that opened the current sub-page (focus goes back there); null = just opened (see initial focus). */
    internal var returnTo: CcTile? = null

    /** True while the Wi-Fi QR shrinks back into its circle; the page turns MAIN when that ends. */
    internal var qrClosing by mutableStateOf(false)

    /** The Launcher settings bucket we just came back from (its row takes focus); null = opened fresh. */
    internal var cameFrom: CcPage? = null

    /** True while the media player shows in the banner's spot (opened by OK on the banner). */
    var playerOpen by mutableStateOf(false)
        internal set

    /** True while the main page's column folds up into [⊜]; the panel calls its onClose when the fold ends. */
    internal var closing by mutableStateOf(false)

    /** Close: the main page (always the bare column) plays its fold first (false = don't hide yet); else true = hide now. */
    internal fun requestClose(): Boolean {
        if (page == CcPage.MAIN) { closing = true; return false }
        return true
    }

    /** Opens straight on a Launcher settings sub-page (first-launch Setup, or reopening Update): Back goes to Launcher settings, then main. */
    fun startAt(page: CcPage) {
        returnTo = CcTile.SETTINGS
        this.page = page
    }

    internal fun open(page: CcPage) {
        qrClosing = false
        playerOpen = false // a sub-page replaces the main page; coming back shows the banner again
        if (page == CcPage.LAUNCHER_SETTINGS) cameFrom = null
        this.page = page
    }

    /** Sub-page → its [parent]; the QR first plays its shrink (see [WifiQrScreen]). */
    internal fun toParent() {
        if (page == CcPage.WIFI_QR) { qrClosing = true; return }
        val parent = page.parent
        cameFrom = if (parent == CcPage.LAUNCHER_SETTINGS) page else null
        page = parent
    }

    /**
     * Back from the host (the overlay's key filter) and the panel's own key handler; true = close the panel.
     * Player open → close it only (focus goes back to the banner); else the same rule as [ccKey].
     */
    fun back(): Boolean {
        if (playerOpen) { playerOpen = false; return false }
        return when (ccKey(page, DockKey.BACK, down = false)) {
            CcAction.Back -> { toParent(); false }
            else -> requestClose()
        }
    }
}

/** Where the Wi-Fi circle sits, so the QR can grow out of it. Not state: only read when the QR opens. */
private class QrOrigin { var root: LayoutCoordinates? = null; var circle: LayoutCoordinates? = null
    fun rect(): Rect? { val r = root ?: return null; val c = circle ?: return null
        return if (r.isAttached && c.isAttached) r.localBoundingBoxOf(c) else null } }

/**
 * NemuiTV's Control Center. Main page = a bare column of glass circles — Inputs, Wi-Fi QR, Next wallpaper,
 * Settings (Launcher settings; Google's Settings is its "Advanced settings" row) — that unfolds down out of [⊜] and
 * folds back into it on close. While Now Playing has a session (playing, or paused in the last 10 min) the Now Playing
 * banner sits left of the column (12 dp gap, same spot as the home screen's); OK on it swaps it for the [MediaPlayer]
 * (Back → banner). Sub-pages are a glass panel. The same composable is shown by the home screen's [⊜] button and
 * over apps (hold Home); both draw this banner. Fills its host; the Wi-Fi QR is full screen. Keys follow [ccKey];
 * first focus = Inputs.
 */
@Composable
fun ControlCenter(
    core: NemuiCore,
    onClose: () -> Unit,
    onOpenApp: (pkg: String) -> Unit,
    modifier: Modifier = Modifier,
    state: ControlCenterState = remember { ControlCenterState() },
    /**
     * Opened over the home screen's top row: the banner / player are already there, so they stay put
     * (drawn at full alpha in the same spot, no fade), and [⊜] cross-fades into the first circle instead of the row
     * fading out and back in. [ccButtonFocused] = [⊜] had focus (drawn white, like it was).
     */
    overHomeTopRow: Boolean = false,
    ccButtonFocused: Boolean = false,
    /**
     * Home's [⊜]: focused at the end of the fold, before the panel leaves (its look-alike still covers it). Focusing
     * it only after the panel had gone left a frame or more with nothing focused: [⊜] see-through, then white again
     * (it read as "collapse, then flash and everything flashes").
     */
    focusOnClose: FocusRequester? = null,
) {
    val tvStatus by core.tv.status.collectAsStateWithLifecycle()
    // Released at the end of the fold so focus can go to [focusOnClose] while the panel is still drawn.
    var trapReleased by remember { mutableStateOf(false) }
    val tvState by core.ha.tvState.collectAsStateWithLifecycle()
    val config by core.configStore.state.collectAsStateWithLifecycle()
    val wifi = (config as? ConfigResult.Ok)?.config?.wifi ?: WifiConfig()
    val inputs = core.tvConfig?.inputs.orEmpty()
    val haStatus by core.ha.status.collectAsStateWithLifecycle()
    // No TV setup or no Home Assistant: the Inputs circle isn't offered (nothing could switch the TV).
    val inputsShown = inputsAvailable(core.tvConfig, haStatus)
    val currentInput = inputs.firstOrNull { it.source == tvState?.source }
    val nowPlaying by core.nowPlaying.current.collectAsStateWithLifecycle()

    var focused by remember { mutableStateOf<CcTile?>(null) }
    val tileFocus = remember { CcTile.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val qrOrigin = remember { QrOrigin() }

    fun perform(action: CcAction) {
        when (action) {
            CcAction.None, CcAction.PassThrough -> Unit
            is CcAction.Open -> { state.returnTo = focused ?: state.returnTo; state.open(action.page) }
            CcAction.Back -> state.toParent()
            // Main page: player open → back to the banner; else the column folds (see ControlCenterState.back).
            CcAction.Close -> if (state.back()) onClose()
        }
    }

    // Music gone: the banner (and the player) leave; don't leave focus nowhere. The column is there in both hosts,
    // so focus goes to Inputs (playerGoneFocus(overApp = true)).
    LaunchedEffect(nowPlaying == null) {
        if (nowPlaying != null || state.page != CcPage.MAIN) return@LaunchedEffect
        val wasOnBanner = state.playerOpen || focused == CcTile.BANNER
        state.playerOpen = false
        if (wasOnBanner && playerGoneFocus(overApp = true) == PlayerGoneFocus.INPUTS) {
            focused = null
            focusSoon(tileFocus.getValue(ccMainFocus(null, bannerShown = false, inputsShown = inputsShown)))
        }
    }

    // The player opens on ⏯; closing it (Back) puts focus back on the banner, or on Inputs if the music went away.
    val playerWasOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(state.playerOpen) {
        val wasOpen = playerWasOpen[0]
        playerWasOpen[0] = state.playerOpen
        if (state.page != CcPage.MAIN) return@LaunchedEffect
        when {
            state.playerOpen -> focusSoon(playPauseFocus)
            wasOpen -> focusSoon(tileFocus.getValue(ccMainFocus(CcTile.BANNER, bannerShown = nowPlaying != null, inputsShown = inputsShown)))
        }
    }

    // Each page takes focus when it appears; the main page puts it back on the tile that opened the sub-page
    // (first open: Inputs; see ccMainFocus).
    LaunchedEffect(state.page) {
        // The inputs list and the QR screen focus themselves.
        if (state.page == CcPage.INPUTS || state.page == CcPage.WIFI_QR) return@LaunchedEffect
        focusSoon(if (state.page != CcPage.MAIN) pageFocus
            else tileFocus.getValue(ccMainFocus(state.returnTo, bannerShown = nowPlaying != null, inputsShown = inputsShown)))
    }

    Box(
        modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown && it.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            if (state.closing) return@onPreviewKeyEvent true
            val action = ccKey(state.page, it.key.toDockKey(), it.type == KeyEventType.KeyDown)
            perform(action)
            action != CcAction.PassThrough
        }.trapFocus(!trapReleased).onGloballyPositioned { qrOrigin.root = it },
    ) {
        // The QR grows out of the Wi-Fi circle, so the main page stays underneath it.
        val panelPage = if (state.page == CcPage.WIFI_QR) CcPage.MAIN else state.page
        fun tile(t: CcTile) = Modifier.focusRequester(tileFocus.getValue(t)).onFocusChanged { f -> if (f.isFocused) focused = t }
        val tvGrey = when (tvStatus) {
            TvStatus.READY -> null
            TvStatus.HA_OFFLINE -> "Home Assistant offline"
            TvStatus.NOT_SET_UP -> "TV not set up"
        }
        val bannerShown = nowPlaying != null && !state.playerOpen
        // ◀ from any circle → the banner while it shows.
        val toBanner = if (bannerShown) Modifier.focusProperties { left = tileFocus.getValue(CcTile.BANNER) } else Modifier
        // The circles (Inputs only with a TV + HA), in order; [m] = per-circle modifier by index (the column animates each one).
        val circles: List<@Composable (Modifier) -> Unit> = listOf<(@Composable (Modifier) -> Unit)?>(
            if (!inputsShown) null else ({ m: Modifier -> CcCircle(CcIcons.Input, "Inputs" + (tvGrey ?: currentInput?.name)?.let { ", $it" }.orEmpty(),
                onClick = { if (tvGrey == null) perform(CcAction.Open(CcPage.INPUTS)) },
                greyed = tvGrey != null, modifier = m.then(toBanner).then(tile(CcTile.INPUTS))) }),
            { m -> CcCircle(CcIcons.QrWifi, "Wi-Fi QR code" + wifi.ssid.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty(),
                onClick = { perform(CcAction.Open(CcPage.WIFI_QR)) },
                modifier = m.then(toBanner).then(tile(CcTile.WIFI)).onGloballyPositioned { qrOrigin.circle = it }) },
            { m -> CcCircle(CcIcons.Wallpaper, "Next wallpaper",
                onClick = { core.requestNextWallpaper() }, modifier = m.then(toBanner).then(tile(CcTile.NEXT_WALLPAPER))) },
            { m -> CcCircle(CcIcons.Gear, "Launcher settings",
                onClick = { perform(CcAction.Open(CcPage.LAUNCHER_SETTINGS)) }, modifier = m.then(toBanner).then(tile(CcTile.SETTINGS))) },
        ).filterNotNull()
        if (panelPage == CcPage.MAIN) {
            // The main page is always one column of bare circles that grows down out of [⊜] and folds back up into
            // it on close; the banner / player beside it fades with it.
            val unfold = remember { Animatable(0f) }
            LaunchedEffect(state.closing) {
                if (state.closing) {
                    // Over home's top row the player folds back into the banner while the column folds into [⊜].
                    val withPlayer = overHomeTopRow && state.playerOpen
                    if (withPlayer) state.playerOpen = false
                    unfold.animateTo(0f, tween(if (withPlayer) PlayerMorph.CLOSE_MS else CcFoldCloseMs, easing = FastOutSlowInEasing))
                    focusOnClose?.let { f ->
                        trapReleased = true
                        withFrameNanos { } // the trap's release is applied
                        runCatching { f.requestFocus() }
                    }
                    state.closing = false
                    onClose()
                } else {
                    unfold.animateTo(1f, tween(CcFoldOpenMs, easing = FastOutSlowInEasing))
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).then(CcPanelPosition).padding(top = TopBarTopPadding),
                horizontalArrangement = Arrangement.spacedBy(CcGap),
            ) {
                val np = nowPlaying
                // Banner ⇄ player morph (PlayerMorph): both share the spot, anchored top-right, while it runs.
                val morph = remember { Animatable(if (state.playerOpen) 1f else 0f) }
                LaunchedEffect(state.playerOpen) {
                    morph.animateTo(if (state.playerOpen) 1f else 0f,
                        tween(if (state.playerOpen) PlayerMorph.OPEN_MS else PlayerMorph.CLOSE_MS, easing = LinearEasing))
                }
                val playerDrawn by remember { derivedStateOf { state.playerOpen || morph.value > 0f } }
                val bannerDrawn by remember { derivedStateOf { !state.playerOpen || PlayerMorph.bannerAlpha(morph.value) > 0f } }
                if (np != null) Box(contentAlignment = Alignment.TopEnd) {
                    if (playerDrawn) {
                        CcPlayer(core, np, playPauseFocus, onOpenApp = { onOpenApp(np.pkg) },
                            active = state.playerOpen, morph = { morph.value },
                            modifier = Modifier.graphicsLayer { alpha = if (overHomeTopRow) 1f else unfold.value })
                    }
                    if (bannerDrawn) {
                        NowPlayingBanner(
                            np, onClick = { state.playerOpen = true }, scroll = true,
                            modifier = Modifier.graphicsLayer {
                                alpha = (if (overHomeTopRow) 1f else unfold.value) * PlayerMorph.bannerAlpha(morph.value)
                            }
                                .focusProperties {
                                    // Focusable until ⏯ takes focus (else it falls to a circle for a frame: a flash).
                                    right = tileFocus.getValue(ccMainFocus(null, bannerShown = false, inputsShown = inputsShown))
                                }
                                .then(tile(CcTile.BANNER)),
                        )
                    }
                }
                Box {
                    // [⊜] as it was, under the column: solid until the column is fully out, so the first circle fades
                    // in / out over it. (Fading both at once added up to a dim grey circle mid-way: the "white flash"
                    // seen on every open/close, caught in a screen recording of the Streamer.)
                    if (overHomeTopRow) CcButtonGhost(ccButtonFocused, Modifier.graphicsLayer { alpha = if (unfold.value < 1f) 1f else 0f })
                    Column(verticalArrangement = Arrangement.spacedBy(CcGap)) {
                        circles.forEachIndexed { i, circle ->
                            circle(Modifier.graphicsLayer {
                                val p = unfold.value
                                alpha = p
                                // Each circle starts stacked on [⊜] and slides down to its place.
                                translationY = -(1f - p) * i * (CcCircleSize + CcGap).toPx()
                            })
                        }
                    }
                }
            }
        } else {
            // Sub-pages (lists of text): the glass panel.
            Column(
                Modifier.align(Alignment.TopEnd).then(CcPanelPosition)
                    .width(CcPanelWidth)
                    .then(Glass.matte(CcPanelShape)) // matte glass: see-through but readable
                    .padding(CcPanelPadding),
                verticalArrangement = Arrangement.spacedBy(CcGap),
            ) {
                when (panelPage) {
                    CcPage.INPUTS -> {
                        InputsList(inputs, tvState?.source, onPick = { input ->
                            // A different input sent: the TV switches away, so close the panel (left open it would
                            // sit unseen over the PS5 and swallow the first presses back). Same input / not sent:
                            // back to the main page.
                            val switched = input.source != tvState?.source && core.tv.selectInput(input)
                            if (switched) onClose() else perform(CcAction.Back)
                        })
                    }
                    CcPage.LAUNCHER_SETTINGS -> LauncherSettingsPage(core, focus = pageFocus, cameFrom = state.cameFrom,
                        onOpen = { perform(CcAction.Open(it)) }, onClose = onClose)
                    CcPage.WALLPAPER_SETTINGS -> WallpaperSettingsPage(core, firstRowFocus = pageFocus)
                    CcPage.SCREENSAVER_SETTINGS -> ScreensaverSettingsPage(core, firstRowFocus = pageFocus)
                    CcPage.SETUP -> SetupPage(core, firstRowFocus = pageFocus, onDone = { perform(CcAction.Back) })
                    CcPage.UPDATE -> UpdatePage(core, firstRowFocus = pageFocus)
                    CcPage.MAIN, CcPage.WIFI_QR -> Unit
                }
            }
        }
        if (state.page == CcPage.WIFI_QR) {
            val origin = remember { qrOrigin.rect() }
            WifiQrScreen(wifi, origin, closing = state.qrClosing, onClosed = { state.qrClosing = false; state.page = CcPage.MAIN })
        }
    }
}

/**
 * The [MediaPlayer] wired to the core: transport, shuffle / repeat, and the Spotify ♥. Looks the ♥ up when it opens
 * and when the track changes (only then: lookup resets it), and clears it when it closes.
 */
@Composable
private fun CcPlayer(
    core: NemuiCore, np: NowPlayingState, playPauseFocus: FocusRequester, onOpenApp: () -> Unit,
    active: Boolean, morph: () -> Float, modifier: Modifier,
) {
    val player by core.nowPlaying.player.collectAsStateWithLifecycle()
    val heart by core.spotify.heart.collectAsStateWithLifecycle()
    LaunchedEffect(np.pkg, np.title) { core.spotify.lookup(np.pkg, np.title) }
    DisposableEffect(Unit) { onDispose { core.spotify.clear() } }
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
        // While it folds back into the banner, focus can't come back in (it's on its way to the banner).
        modifier = modifier.focusProperties { onEnter = { if (!active) cancelFocusChange() } }.focusGroup(),
        morph = morph,
    )
}

/** Asks [target] for focus, retrying for a few frames while it isn't laid out yet. */
private suspend fun focusSoon(target: FocusRequester) {
    repeat(10) {
        if (runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return
        withFrameNanos { }
    }
}

@Composable
private fun CcCircle(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, greyed: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(CcCircleSize).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = Glass.colors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = Glass.border(CircleShape),
        glow = Glass.glow(),
    ) {
        Box(Modifier.fillMaxSize().alpha(if (greyed) 0.5f else 1f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(CcIconSize))
        }
    }
}

/** A look-alike of home's [⊜] (not focusable), drawn where it sits, for the cross-fade into the first circle. */
@Composable
private fun CcButtonGhost(focused: Boolean, modifier: Modifier) {
    Box(
        modifier.size(CcCircleSize)
            .graphicsLayer { if (focused) { scaleX = 1.12f; scaleY = 1.12f } }
            .then(if (focused) Modifier.background(Jt.FrostFocused, CircleShape) else Glass.surface(CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        ControlCenterGlyph(if (focused) Jt.TextOnFocus else Jt.TextPrimary, size = 18.dp)
    }
}

/** Opens [pkg]'s TV launch screen (or its phone one); for the player's art. */
fun Context.launchPackage(pkg: String) {
    val pm = packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w("NemuiTV", "couldn't open $pkg", it) }
}

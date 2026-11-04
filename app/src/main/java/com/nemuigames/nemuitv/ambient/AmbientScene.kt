package com.nemuigames.nemuitv.ambient

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nemuigames.nemuitv.config.ConfigResult
import com.nemuigames.nemuitv.config.WallpaperOrder
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.ha.HaPresence
import com.nemuigames.nemuitv.home.ClockWeather
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.home.NowPlayingBanner
import com.nemuigames.nemuitv.home.TopBarTopPadding
import com.nemuigames.nemuitv.wallpaper.PosterCache
import com.nemuigames.nemuitv.wallpaper.WallpaperLayer
import com.nemuigames.nemuitv.wallpaper.WallpaperPlaylist
import com.nemuigames.nemuitv.wallpaper.listWallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "NemuiTV"
private const val DIM_ALPHA = 0.55f
private const val CLOCK_ALPHA = 0.5f
private const val WALLPAPER_EVERY_MS = 5 * 60_000L
private const val GLIDE_MS = 20_000
private const val BLACK_FADE_MS = 3_000

// Grayscale for the clock + weather (the weather glyph is a colored emoji) and the Now Playing banner (album art).
private val Grayscale = RenderEffect.createColorFilterEffect(
    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }),
).asComposeRenderEffect()

/** Drawn gray at [CLOCK_ALPHA]: everything ambient shows over the wallpaper (clock + weather, the banner). */
private fun Modifier.ambientGray(): Modifier = graphicsLayer {
    alpha = CLOCK_ALPHA
    renderEffect = Grayscale
}

/**
 * The dream's side of the fades ([AmbientDream] owns it). [translucentStart] = the window starts see-through (Home
 * visible under it): the scene first fades black over Home, then calls [onOpaque]. [exiting] = waking: fade to black
 * (the dream finishes after [AMBIENT_EXIT_FADE_MS]).
 */
class AmbientFade(val translucentStart: Boolean, private val onOpaque: () -> Unit) {
    var opaque by mutableStateOf(!translucentStart)
        private set
    var exiting by mutableStateOf(false)

    fun becomeOpaque() { if (!opaque) { opaque = true; onOpaque() } }
}

/**
 * The screensaver: the home wallpaper dimmed, the clock + weather gray in their home spot (gliding through a small
 * burn-in cycle), then pure black after "Black after". No dock, no [⊜]. While music plays, the home Now Playing banner
 * sits in the top-right corner ([⊜]'s spot), gray too, gliding through the mirrored cycle.
 */
@Composable
fun AmbientScene(core: NemuiCore, startAt: String?, fade: AmbientFade) {
    val context = LocalContext.current
    val prefs by core.settings.prefs.collectAsState()
    val weather by core.ha.weather.collectAsState()
    val disconnectedSince by core.ha.disconnectedSince.collectAsState()
    val haStatus by core.ha.status.collectAsState()
    val nowPlaying by core.nowPlaying.current.collectAsState()

    // Ambient time, ticked once a second until black (black stays black, so no more recompositions for hours).
    val startMs = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(prefs.blackAfterMin) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - startMs
            if (isBlack(elapsed, prefs.blackAfterMin)) break
            delay(1_000)
        }
    }
    val black = isBlack(elapsed, prefs.blackAfterMin)

    // Own playlist, starting on the video Home was showing ([startAt], picked by the dream as it attached).
    val playlist = remember {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "wallpapers")
        val files = listWallpapers(dir).map { it.absolutePath }
        val cfgOrder = (core.configStore.state.value as? ConfigResult.Ok)?.config?.wallpaperOrder ?: WallpaperOrder.SHUFFLE
        WallpaperPlaylist(files, core.settings.prefs.value.effectiveOrder(cfgOrder), startAt = startAt)
    }
    var wallpaper by remember { mutableStateOf(playlist.current()) }
    // Fades (AmbientTransitions.kt): black over Home → opaque → own wallpaper once Home has stopped → fade in.
    // [cover] = the black layer on top of everything; [wallpaperOn] = the scene's wallpaper player may run.
    val cover = remember { Animatable(if (fade.translucentStart) 0f else 1f) }
    var wallpaperOn by remember { mutableStateOf(false) }
    LaunchedEffect(black) {
        if (black) return@LaunchedEffect // nothing to change behind the black
        while (true) { delay(WALLPAPER_EVERY_MS); wallpaper = playlist.advance() }
    }
    val posters = remember { PosterCache(File(context.cacheDir, "posters")) }
    val poster by produceState<Bitmap?>(null, wallpaper) {
        value = wallpaper?.let { f -> withContext(Dispatchers.IO) { posters.load(File(f)) } }
    }
    val wallpaperReady by rememberUpdatedState(wallpaper == null || poster != null)
    LaunchedEffect(Unit) {
        if (fade.translucentStart) {
            cover.animateTo(1f, tween(AMBIENT_START_FADE_OUT_MS)) // cancelled (ends here) if waking meanwhile
            if (fade.exiting) return@LaunchedEffect
            fade.becomeOpaque()
        }
        val opaqueAt = SystemClock.elapsedRealtime()
        while (!ambientWallpaperMayStart(core.homeStarted.value, SystemClock.elapsedRealtime() - opaqueAt)) delay(50)
        wallpaperOn = true
        while (!ambientRevealed(wallpaperReady, SystemClock.elapsedRealtime() - opaqueAt)) delay(50)
        if (!fade.exiting) cover.animateTo(0f, tween(AMBIENT_FADE_IN_MS))
    }
    LaunchedEffect(fade.exiting) { if (fade.exiting) cover.animateTo(1f, tween(AMBIENT_EXIT_FADE_MS)) }
    val coverShown by remember { derivedStateOf { cover.value > 0f } }

    val blackAlpha by animateFloatAsState(if (black) 1f else 0f, tween(BLACK_FADE_MS), label = "black")
    val (sx, sy) = shiftOffsetDp(shiftStep(elapsed))
    val offsetX by animateDpAsState(sx.dp, tween(GLIDE_MS), label = "shiftX")
    val offsetY by animateDpAsState(sy.dp, tween(GLIDE_MS), label = "shiftY")
    val (bx, by) = bannerShiftDp(shiftStep(elapsed))
    val bannerX by animateDpAsState(bx.dp, tween(GLIDE_MS), label = "bannerX")
    val bannerY by animateDpAsState(by.dp, tween(GLIDE_MS), label = "bannerY")
    // Fades (200 ms, like home) on play / pause; keeps the last state while fading out so the text doesn't vanish first.
    // In black it's covered by the black fade like everything else, then left out.
    // An alert (drawn by the overlay) takes the banner's spot while it shows.
    val alerts by core.alerts.ui.collectAsState()
    val bannerOn = ambientBannerShown(nowPlaying?.playing, black = blackAlpha >= 1f) &&
        alerts.doorbell == null && alerts.banners.isEmpty()
    var bannerState by remember { mutableStateOf(nowPlaying) }
    if (bannerOn) bannerState = nowPlaying
    val bannerAlpha by animateFloatAsState(if (bannerOn) 1f else 0f, tween(200), label = "banner")
    val stale = HaPresence.showsProblem(haStatus, disconnectedSince, System.currentTimeMillis())

    // See-through until opaque (Home shows under the first fade); nothing of the scene is drawn before that.
    Box(if (fade.opaque) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxSize()) {
        if (blackAlpha < 1f && fade.opaque) {
            // No wallpapers (or all broken) → just black behind the clock (WallpaperLayer would draw its gradient).
            // Not before Home has stopped (wallpaperOn): only one wallpaper player at a time.
            wallpaper?.takeIf { wallpaperOn }?.let { file ->
                WallpaperLayer(
                    file = file,
                    poster = poster,
                    playing = !black,
                    onError = { bad ->
                        wallpaper = playlist.markBroken(bad)
                        Log.w(TAG, "ambient wallpaper can't play, skipping: ${File(bad).name}")
                    },
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DIM_ALPHA)))
            }
            // Same spot as on the home screen (HomeScreen's safe-area Column → Row → ClockWeather).
            Box(Modifier.fillMaxSize().padding(horizontal = Jt.SafeAreaH, vertical = Jt.SafeAreaV)) {
                ClockWeather(
                    weather, stale,
                    modifier = Modifier.offset(offsetX, offsetY).ambientGray(),
                    clock = prefs.clock, unit = prefs.unit,
                )
                // Where [⊜] is on home (same top as the top bar there). Not focusable: AmbientDream handles every key itself.
                val np = bannerState
                if (np != null && bannerAlpha > 0f) {
                    NowPlayingBanner(
                        np, onClick = {}, scroll = false,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = TopBarTopPadding)
                            .offset(bannerX, bannerY).graphicsLayer { alpha = bannerAlpha }.ambientGray()
                            .focusProperties { canFocus = false },
                    )
                }
            }
        }
        if (blackAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = blackAlpha }.background(Color.Black))
        if (coverShown) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.value }.background(Color.Black))
    }
}

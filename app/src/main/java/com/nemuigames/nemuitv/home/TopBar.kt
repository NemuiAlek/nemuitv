package com.nemuigames.nemuitv.home

import com.nemuigames.nemuitv.controlcenter.PlayerMorph
import com.nemuigames.nemuitv.alerts.AlertUi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.nowplaying.NowPlayingState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface

/** No font padding, line boxes trimmed to the glyphs: the two lines sit in the middle of the pill. */
private val BannerTitleStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.SemiBold,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)
private val BannerArtistStyle = BannerTitleStyle.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)

/** Fixed, so it never jumps with the song: fits left of [⊜] on the 960×540 canvas with the clock still clear. */
private val BannerWidth = 150.dp // was 300; half the size reads better from the couch

/** Where Up from the dock goes: the Now Playing banner if it's showing, else straight to [⊜]. */
enum class TopBarTarget { BANNER, CONTROL_CENTER }
fun upFromDockTarget(bannerShowing: Boolean) = if (bannerShowing) TopBarTarget.BANNER else TopBarTarget.CONTROL_CENTER

/** [⊜] and the banner hide while the in-app Control Center is open or the accessibility-overlay one (hold Home) shows. */
fun topRowHidden(panelOpen: Boolean, overlayShowing: Boolean): Boolean = panelOpen || overlayShowing

/** The banner's first line: the title, or the app's label when there is none. */
fun bannerTitle(np: NowPlayingState): String = np.title?.takeIf { it.isNotBlank() } ?: np.appLabel

/** The banner's second line: the artist; null (line left out) when there is none. */
fun bannerArtist(np: NowPlayingState): String? = np.artist?.takeIf { it.isNotBlank() }

/**
 * Top-right row, level with the clock: Now Playing banner (alerts join it in M4), then the [⊜] Control Center
 * button. [⊜] is anchored at the right; the banner sits to its left and fades (200 ms) in/out without moving it.
 * Banner (couch test 2): fixed [BannerWidth]; album art, title over artist (a long title scrolls while the home
 * screen is resumed), equalizer on the right.
 * The banner shows whenever Now Playing has a session (playing, or paused within 10 min — same rule as the panel's
 * tile); its equalizer bounces while playing and stands still while paused. Both are glass like the ⌂ tile ([Glass]).
 * [panelOpen] = the Control Center is open: it covers this row (its panel is translucent), so the banner and [⊜]
 * fade out; [⊜] stays focusable so focus can return to it on close. [bannerActive] is what makes the banner focusable.
 * [alertUi]: while an alert shows, the alert stack takes the banner's spot so the banner fades out too.
 */
@Composable
fun TopBar(
    onControlCenter: () -> Unit,
    controlCenterFocus: FocusRequester,
    nowPlayingFlow: StateFlow<NowPlayingState?>,
    panelOpen: Boolean,
    overlayShowing: StateFlow<Boolean>,
    alertUi: StateFlow<AlertUi>,
    bannerFocus: FocusRequester,
    onOpenBanner: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The home screen's own player is open in the banner's spot: the banner hides (not [⊜]). */
    playerOpen: Boolean = false,
    /** The banner ⇄ player morph's progress ([PlayerMorph]): the banner's fade while the player grows out of it. */
    bannerMorph: () -> Float = { 0f },
) {
    // Collected here, not by the home screen: a Now Playing update recomposes only this row.
    val nowPlaying by nowPlayingFlow.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val alerts by alertUi.collectAsStateWithLifecycle()
    val overlayUp by overlayShowing.collectAsStateWithLifecycle()
    val hidden = topRowHidden(panelOpen, overlayUp)
    val alertUp = alerts.doorbell != null || alerts.banners.isNotEmpty()
    val present = nowPlaying != null
    // Under a Control Center the row goes at once (no fade): the panel draws the same banner in the same spot and
    // cross-fades [⊜] into its first circle itself, so nothing blinks out and back.
    val bannerVisible = present && !alertUp
    val bannerActive = bannerVisible && !hidden && !playerOpen
    // Keep the last state while fading out, so the text doesn't vanish before the pill does.
    var shown by remember { mutableStateOf(nowPlaying) }
    if (nowPlaying != null) shown = nowPlaying
    // The player open/close fades it by the morph instead (bannerMorph), so it leaves / comes back with the box.
    val alpha by animateFloatAsState(if (bannerVisible) 1f else 0f, tween(200), label = "banner")
    val buttonAlpha = if (hidden) 0f else 1f
    // Focus must never be lost: if the session goes away (or an alert takes the spot) while the banner has focus,
    // move to [⊜]. (When the panel opens instead, the panel takes focus and gives it back to [⊜] on close.)
    val hadFocus = remember { booleanArrayOf(false) }
    val bannerGone = !present || alertUp
    LaunchedEffect(bannerGone) {
        if (bannerGone && hadFocus[0]) {
            hadFocus[0] = false
            withFrameNanos { }
            repeat(10) {
                if (runCatching { controlCenterFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val s = shown
        if (s != null && alpha > 0f) {
            NowPlayingBanner(
                s, onClick = { onOpenBanner(s.pkg) },
                // Scrolls only while it can be seen: not behind an app, not under the panel.
                scroll = bannerActive && lifecycle.isAtLeast(Lifecycle.State.RESUMED),
                modifier = Modifier
                    .graphicsLayer { this.alpha = if (hidden) 0f else alpha * PlayerMorph.bannerAlpha(bannerMorph()) }
                    .focusRequester(bannerFocus)
                    // Still focusable while the player is open: OK leaves focus here until ⏯ takes it, else it falls
                    // to [⊜] for a frame (a white flash). The open player traps focus, so ◀ ▶ can't reach it.
                    .focusProperties { canFocus = bannerVisible && !hidden }
                    .onFocusChanged { if (it.hasFocus) hadFocus[0] = true else if (bannerActive) hadFocus[0] = false },
            )
            Spacer(Modifier.width(12.dp))
        }
        Surface(
            onClick = onControlCenter,
            modifier = Modifier.size(40.dp).graphicsLayer { this.alpha = buttonAlpha }.focusRequester(controlCenterFocus),
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = Glass.colors(),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            border = Glass.border(CircleShape),
            glow = Glass.glow(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ControlCenterGlyph(LocalContentColor.current, size = 18.dp)
            }
        }
    }
}

/** The top bar's distance below the safe-area top on the home screen (level with the clock); ambient uses it too. */
val TopBarTopPadding = 14.dp

/**
 * The glass Now Playing pill: album art (or a glass square), title over artist, equalizer (bouncing while [np] plays).
 * Fixed [BannerWidth]. [scroll] = a long title scrolls (else it's cut with …). Shown by [TopBar] on the home screen and,
 * gray and unfocusable, by the ambient screensaver. Focus/alpha come in through [modifier].
 */
@Composable
fun NowPlayingBanner(np: NowPlayingState, onClick: () -> Unit, scroll: Boolean, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp).width(BannerWidth),
        shape = ClickableSurfaceDefaults.shape(shape = Jt.TileShape),
        colors = Glass.colors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f), // a wide pill: 1.1 like a tile is too much
        border = Glass.border(Jt.TileShape),
        glow = Glass.glow(),
    ) {
        // Fill the pill's height so the row (and the text) is centered in it, not stacked at its top.
        Row(Modifier.fillMaxHeight().padding(start = 5.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val artShape = RoundedCornerShape(7.dp)
            np.art?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(30.dp).clip(artShape))
            } ?: Box(Modifier.size(30.dp).then(Glass.surface(artShape)))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bannerTitle(np), style = BannerTitleStyle, maxLines = 1,
                    overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (scroll) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                bannerArtist(np)?.let { artist ->
                    Text(artist, style = BannerArtistStyle, color = LocalContentColor.current.copy(alpha = 0.78f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Equalizer(np.playing, LocalContentColor.current, Modifier.size(12.dp))
        }
    }
}

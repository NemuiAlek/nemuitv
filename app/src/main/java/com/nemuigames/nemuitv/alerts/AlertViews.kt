package com.nemuigames.nemuitv.alerts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.camera.CameraBank
import com.nemuigames.nemuitv.camera.CameraFeed
import com.nemuigames.nemuitv.camera.FeedState
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.home.TopBarTopPadding
import com.nemuigames.nemuitv.hub.HubWarm
import com.nemuigames.nemuitv.hub.lastSeenText
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import java.time.ZoneId
import org.webrtc.RendererCommon

// Layout values: Global Constraints "Placement", "Doorbell pop-up", "Expanded", "Banners" (+ mockup alerts-v2.html).
private val StackGap = 8.dp
/** On NemuiTV home the stack takes the Now Playing banner's place: left of the 40 dp [⊜] button + its 12 dp gap. */
internal val HomeCcButtonClearance = 40.dp + 12.dp
private val PopupWidth = 120.dp
private val PopupShape = RoundedCornerShape(12.dp)
private val ExpandedShape = RoundedCornerShape(16.dp)
private val ExpandedLabelRow = 34.dp
private val BannerWidth = 150.dp
private val BannerHeight = 40.dp
private val BannerShape = RoundedCornerShape(10.dp)
private val CountdownHeight = 2.dp
private const val FADE_MS = 400
private const val VIDEO_ASPECT = 3f / 4f // the doorbell's box; a differently shaped video is cropped to fill it

private val CamGradient = Brush.linearGradient(listOf(Color(0xFF3A4250), Color(0xFF20252E)))
private val PillShape = RoundedCornerShape(4.dp)
private val PillColor = Color.Black.copy(alpha = 0.45f)
private val LiveRed = Color(0xFFE5484D)
private val Dim = Color.Black.copy(alpha = 0.35f)
private val Readable = TextStyle(shadow = Jt.Legible) // clear glass over bright video

/**
 * Everything the alerts show, over a full-screen, non-focusable layer: the stack top-right in the safe area (doorbell
 * pop-up, then up to 3 banners, newest first, 8 dp apart; level with [⊜], just left of it when [homeInFront], where
 * the Now Playing banner would be — TopBar and ambient hide that banner while an alert shows) and,
 * when expanded, the black 35 % dim + the big centered doorbell panel. [cameras] is only called when a doorbell with a
 * camera shows (the bank is lazy). Never focusable: the remote stays with the app (OK/Back are taken by [AlertKeys]).
 */
@Composable
fun AlertStack(alerts: AlertCenter, cameras: () -> CameraBank, homeInFront: Boolean, ccOpen: Boolean) {
    val ui by alerts.ui.collectAsState()
    // A Control Center (or home's player) uses this corner: the banners fade out meanwhile and come back after
    // (not dismissed). A ring closes the Control Center instead (NemuiCore.closeCcRequests).
    val bannersAlpha by animateFloatAsState(if (ccOpen) 0f else 1f, tween(160), label = "alertsAside")
    val doorbell = ui.doorbell
    Box(Modifier.fillMaxSize().focusProperties { canFocus = false }) {
        if (doorbell?.expanded == true) Box(Modifier.fillMaxSize().background(Dim))
        Column(
            Modifier.align(Alignment.TopEnd)
                // Where the Now Playing banner sits (home's top row, ambient): level with [⊜].
                .padding(top = Jt.SafeAreaV + TopBarTopPadding, end = Jt.SafeAreaH + if (homeInFront) HomeCcButtonClearance else 0.dp),
            verticalArrangement = Arrangement.spacedBy(StackGap),
            horizontalAlignment = Alignment.End,
        ) {
            if (doorbell != null && !doorbell.expanded) key("doorbell") { DoorbellPopup(doorbell, cameras) }
            if (bannersAlpha > 0f) Column(
                Modifier.graphicsLayer { alpha = bannersAlpha },
                verticalArrangement = Arrangement.spacedBy(StackGap),
                horizontalAlignment = Alignment.End,
            ) {
                ui.banners.forEach { b -> key(b.key) { AlertBanner(b, alerts.clock) } }
            }
        }
        if (doorbell?.expanded == true) {
            DoorbellExpanded(doorbell, cameras)
        }
    }
}

/** Mockup A: 120 dp wide glass card, 3:4 live video (red ● LIVE), `🔔 <name>` and the hint. */
@Composable
fun DoorbellPopup(d: DoorbellUi, cameras: () -> CameraBank) {
    Column(Modifier.width(PopupWidth).clip(PopupShape).then(Glass.surface(PopupShape))) {
        // No camera on the doorbell's device: label only, no video area (spec §6).
        d.cameraId?.let { id ->
            DoorbellVideo(cameras(), id, small = true, Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT))
        }
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(
                "🔔 ${d.name}", fontSize = 12.sp, color = Jt.TextPrimary, style = Readable,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Non-breaking spaces: if it doesn't fit on one line it wraps after the "·", never mid-phrase.
            Text(
                "OK to view · Back to close", fontSize = 9.sp, lineHeight = 11.sp,
                color = Jt.TextSecondary, style = Readable, maxLines = 2,
            )
        }
    }
}

/** OK on the pop-up: big centered glass panel, full height minus the 27 dp margins; 3:4 video + label row. */
@Composable
fun DoorbellExpanded(d: DoorbellUi, cameras: () -> CameraBank, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val panelH = maxHeight - Jt.SafeAreaV * 2
        val hasVideo = d.cameraId != null
        val videoH = panelH - ExpandedLabelRow
        val panelW = if (hasVideo) videoH * VIDEO_ASPECT else 320.dp
        Column(
            Modifier.width(panelW).then(if (hasVideo) Modifier.height(panelH) else Modifier)
                .clip(ExpandedShape).then(Glass.surface(ExpandedShape)),
        ) {
            d.cameraId?.let { id ->
                DoorbellVideo(cameras(), id, small = false, Modifier.fillMaxWidth().height(videoH))
            }
            Row(
                Modifier.fillMaxWidth().height(ExpandedLabelRow).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "🔔 ${d.name}", fontSize = 14.sp, color = Jt.TextPrimary, style = Readable,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text("Back = resume show", fontSize = 12.sp, color = Jt.TextSecondary, style = Readable, maxLines = 1)
            }
        }
    }
}

/**
 * The doorbell's live video in a fixed box: the renderer is sized to the video's own aspect and cropped to the box
 * (the TextureView renderer can't letterbox), the feed's state on top. Its own composable with stable inputs, so
 * the stack's recompositions (rings, banners) never touch the renderer; the aspect and state are collected in here.
 * The renderer is one of the camera's sinks (fan-out: the hub tile can show the same camera) for exactly as long as
 * its view exists; [AndroidView]'s onRelease removes it on every dispose path.
 */
@Composable
private fun DoorbellVideo(bank: CameraBank, cameraId: String, small: Boolean, modifier: Modifier) {
    val aspect by bank.videoAspect(cameraId).collectAsState()
    BoxWithConstraints(modifier.clipToBounds().background(CamGradient), contentAlignment = Alignment.Center) {
        val boxAspect = maxWidth / maxHeight
        val (w, h) = if (aspect > boxAspect) maxHeight * aspect to maxHeight else maxWidth to maxWidth / aspect
        AndroidView(
            factory = { ctx ->
                VideoTextureViewRenderer(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    init(bank.eglContext, NoRendererEvents)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    bank.addSink(cameraId, this)
                }
            },
            // Same as the hub tile: stop frames to this renderer first; the library releases its EglRenderer in
            // onDetachedFromWindow, which follows when the view leaves the window (pop-up closed, expanded, overlay gone).
            onRelease = { bank.removeSink(cameraId, it) },
            modifier = Modifier.requiredSize(w, h).focusProperties { canFocus = false },
        )
        FeedStateOverlay(bank.feed(cameraId), small)
    }
}

/** `Connecting…` until the first frame, red `● LIVE` while live, `Couldn't connect` while it retries. */
@Composable
private fun FeedStateOverlay(feed: CameraFeed, small: Boolean) {
    val state by feed.state.collectAsState()
    val textSize: TextUnit = if (small) 9.sp else 12.sp
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            FeedState.Live -> Text(
                "● LIVE", fontSize = textSize, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(if (small) 6.dp else 10.dp)
                    .background(LiveRed, PillShape).padding(horizontal = 4.dp),
            )
            else -> {
                val text = when (s) {
                    is FeedState.Reconnecting -> when {
                        s.lastFrameAtMs == null -> "Couldn't connect"
                        s.showLastSeen -> lastSeenText(s.lastFrameAtMs, ZoneId.systemDefault())
                        else -> "Reconnecting…"
                    }
                    else -> "Connecting…"
                }
                Text(
                    text, fontSize = textSize, color = Jt.TextPrimary, maxLines = 2,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp)
                        .background(PillColor, PillShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 150 × 40 dp glass: text, area below (no icon); a warm-yellow line along the bottom runs out over the 30 s, then the
 * banner fades (in the last [FADE_MS], before AlertCenter drops it at 30 s). A restart (same entity again) has a new
 * [BannerUi.shownAtMs], which restarts both. [clock] = AlertCenter's clock (the one shownAtMs comes from). Line and fade are read in the draw phase: no recomposition per frame.
 */
@Composable
fun AlertBanner(b: BannerUi, clock: () -> Long) {
    val elapsed = (clock() - b.shownAtMs).coerceIn(0L, ALERT_SHOW_MS)
    val lineMs = ALERT_SHOW_MS - FADE_MS
    val line = remember(b.shownAtMs) { Animatable((1f - elapsed.toFloat() / lineMs).coerceIn(0f, 1f)) }
    val alpha = remember(b.shownAtMs) { Animatable(1f) }
    LaunchedEffect(b.shownAtMs) {
        val left = (lineMs - elapsed).coerceAtLeast(0L).toInt()
        if (left > 0) line.animateTo(0f, tween(left, easing = LinearEasing))
        alpha.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
    }
    Box(
        Modifier.size(BannerWidth, BannerHeight).graphicsLayer { this.alpha = alpha.value }
            .clip(BannerShape).then(Glass.surface(BannerShape)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Text only, no icon.
            Column {
                Text(
                    b.text.text, fontSize = 12.sp, color = Jt.TextPrimary, style = Readable,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                b.text.sub?.let {
                    Text(it, fontSize = 10.sp, color = Jt.TextSecondary, style = Readable, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(CountdownHeight)
                .drawBehind { drawRect(HubWarm, size = Size(size.width * line.value, size.height)) },
        )
    }
}

private object NoRendererEvents : RendererCommon.RendererEvents {
    override fun onFirstFrameRendered() {}
    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
}

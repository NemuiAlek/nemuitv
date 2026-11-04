package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.camera.CameraBank
import com.nemuigames.nemuitv.camera.CameraFeed
import com.nemuigames.nemuitv.camera.FeedState
import com.nemuigames.nemuitv.home.Jt
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import java.time.ZoneId
import kotlinx.coroutines.flow.combine
import org.webrtc.RendererCommon

private val CamShape = RoundedCornerShape(8.dp)
private val CamGradient = Brush.linearGradient(listOf(Color(0xFF3A4250), Color(0xFF20252E)))
private val PillShape = RoundedCornerShape(4.dp)
private val PillColor = Color.Black.copy(alpha = 0.45f)

/**
 * The Overview's live cameras: one row, [height] tall, centered. Every tile shares one height and is as wide as its
 * own video (height × aspect; the TextureView renderer can't letterbox). [height] is sized for the widest real case
 * (doorbell 3:4 + two 16:9); while a video's aspect is still unknown (16:9 default) the tiles can need more width than
 * the pane has, so they shrink together inside the same [height] slot and nothing below them moves. Not focusable.
 */
@Composable
fun CameraRow(bank: CameraBank, cameras: List<String>, names: Map<String, String>, height: Dp) {
    if (cameras.isEmpty()) return
    // The sum of the aspects is collected here (changes once per camera, on its first frame), not in the pane.
    val aspectSum by remember(bank, cameras) {
        combine(cameras.map(bank::videoAspect)) { it.sum() }
    }.collectAsState(initial = cameras.sumOf { bank.videoAspect(it).value.toDouble() }.toFloat())
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        val gaps = CameraGap * (cameras.size - 1)
        val tileH = minOf(height, (maxWidth - gaps) / aspectSum)
        Row(horizontalArrangement = Arrangement.spacedBy(CameraGap), verticalAlignment = Alignment.CenterVertically) {
            cameras.forEach { id -> key(id) { CameraTile(bank, id, names[id] ?: entityName(id, null), tileH) } }
        }
    }
}

/**
 * One live camera: a [VideoTextureViewRenderer] on the bank's EGL context (one of the camera's sinks while the
 * tile exists; released when its view leaves the window), the name bottom-left and the feed's state overlay. The tile
 * collects its own aspect and state, so frames and state changes never recompose the Overview.
 */
@Composable
fun CameraTile(bank: CameraBank, entityId: String, name: String, height: Dp) {
    val aspect by bank.videoAspect(entityId).collectAsState()
    Box(Modifier.height(height).width(height * aspect).clip(CamShape).background(CamGradient)) {
        AndroidView(
            factory = { ctx ->
                VideoTextureViewRenderer(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    init(bank.eglContext, NoRendererEvents)
                    // The tile already has the video's aspect; FILL only hides sub-dp rounding at the edges.
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    bank.addSink(entityId, this)
                }
            },
            // Remove the sink first, so the runner stops handing frames to this renderer (other renderers of the same
            // camera, e.g. the doorbell pop-up, keep theirs). Its EglRenderer is released by the library's own
            // onDetachedFromWindow (the class has no public release()), which follows when Compose removes the view.
            onRelease = { bank.removeSink(entityId, it) },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        CameraOverlay(bank.feed(entityId))
        Text(
            name, fontSize = 11.sp, color = Jt.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp, end = 8.dp)
                .background(PillColor, PillShape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Its own scope: a state change recomposes only this text, not the renderer or the name. */
@Composable
private fun CameraOverlay(feed: CameraFeed) {
    val state by feed.state.collectAsState()
    val text = when (val s = state) {
        FeedState.Idle, FeedState.Connecting -> "Connecting…"
        FeedState.Live -> null
        is FeedState.Reconnecting ->
            if (s.showLastSeen && s.lastFrameAtMs != null) lastSeenText(s.lastFrameAtMs, ZoneId.systemDefault())
            else "Reconnecting…"
    } ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text, fontSize = 10.sp, color = Jt.TextPrimary, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp).background(PillColor, PillShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal val CameraGap = 8.dp

private object NoRendererEvents : RendererCommon.RendererEvents {
    override fun onFirstFrameRendered() {}
    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
}

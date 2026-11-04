package com.nemuigames.nemuitv.controlcenter

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.nowplaying.NowPlayingState
import com.nemuigames.nemuitv.nowplaying.PlayerInfo
import com.nemuigames.nemuitv.nowplaying.RepeatMode
import com.nemuigames.nemuitv.nowplaying.mmss
import com.nemuigames.nemuitv.nowplaying.positionAt
import com.nemuigames.nemuitv.nowplaying.progressFraction
import com.nemuigames.nemuitv.spotify.Heart
import com.nemuigames.nemuitv.spotify.HeartUi

enum class MediaButton { PREVIOUS, PLAY_PAUSE, NEXT }

private val PlayerWidth = 230.dp
private val PlayerArtShape = RoundedCornerShape(7.dp)
private val SpotifyGreen = Color(0xFF1ED760)
private val PlayerDark = Color(0xFF141414)

/**
 * The Control Center media player (layout B, 230 dp wide): art + song/artist + ♥; shuffle ⏮ ⏯ ⏭ repeat; elapsed bar.
 * Focus: ⏯ via [playPauseFocus]; ▲ from row 2 lands on the art, ▼ from row 1 on ⏯. Shuffle / repeat / ♥ hide
 * when unsupported ([PlayerInfo.shuffle] / [PlayerInfo.repeat] null, [HeartUi.heart] HIDDEN).
 */
@Composable
fun MediaPlayer(
    np: NowPlayingState,
    player: PlayerInfo?,
    heart: HeartUi,
    onButton: (MediaButton) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onHeart: () -> Unit,
    onOpenApp: () -> Unit,
    playPauseFocus: FocusRequester,
    modifier: Modifier = Modifier,
    /** The banner ⇄ player morph's progress ([PlayerMorph]); read in the draw phase. 1 = fully open. */
    morph: () -> Float = { 1f },
) {
    fun row(i: Int) = Modifier.graphicsLayer { alpha = PlayerMorph.rowAlpha(i, morph()) }
    val artFocus = remember { FocusRequester() }
    Column(
        modifier.width(PlayerWidth).playerMorph(morph).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: art (OK = open the app), song over artist, ♥.
        Row(row(0).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpenApp,
                modifier = Modifier.size(48.dp).focusRequester(artFocus).focusProperties { down = playPauseFocus },
                shape = ClickableSurfaceDefaults.shape(shape = PlayerArtShape),
                colors = playerColors(Jt.TextPrimary),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                val art = np.art
                if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Jt.Frost, PlayerArtShape))
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(np.title ?: np.appLabel, fontSize = 14.sp, lineHeight = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE))
                val sub = np.artist ?: if (np.title != null) np.appLabel else null
                if (sub != null) {
                    Text(sub, fontSize = 11.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (heart.heart != Heart.HIDDEN) {
                val liked = heart.heart == Heart.LIKED
                IconButton(
                    icon = if (liked) CcIcons.Heart else CcIcons.HeartOutline,
                    tint = if (liked) SpotifyGreen else Color.White,
                    onClick = onHeart,
                    modifier = Modifier.alpha(if (heart.busy) 0.5f else 1f).focusProperties { down = playPauseFocus },
                )
            }
        }
        // Row 2: shuffle far left … repeat far right.
        Row(row(1).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val toArt = Modifier.focusProperties { up = artFocus }
            val shuffle = player?.shuffle
            if (shuffle != null) {
                IconButton(CcIcons.Shuffle, if (shuffle) SpotifyGreen else Color.White, onShuffle, toArt)
            }
            MediaButton.entries.forEach { b ->
                val isPlay = b == MediaButton.PLAY_PAUSE
                Surface(
                    onClick = { onButton(b) },
                    modifier = toArt.size(if (isPlay) 28.dp else 22.dp)
                        .then(if (isPlay) Modifier.focusRequester(playPauseFocus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                    colors = if (isPlay) ClickableSurfaceDefaults.colors(
                        containerColor = Color.White, focusedContainerColor = Color.White,
                        contentColor = PlayerDark, focusedContentColor = PlayerDark,
                    ) else playerColors(Jt.TextPrimary),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MediaGlyph(b, np.playing, LocalContentColor.current, if (isPlay) 13.dp else 12.dp)
                    }
                }
            }
            val repeat = player?.repeat
            if (repeat != null) {
                IconButton(
                    if (repeat == RepeatMode.ONE) CcIcons.RepeatOne else CcIcons.Repeat,
                    if (repeat == RepeatMode.OFF) Color.White else SpotifyGreen, onRepeat, toArt,
                )
            }
        }
        // Row 3: elapsed · bar · total. Not focusable.
        ProgressRow(player, row(2))
    }
}

@Composable
private fun ProgressRow(player: PlayerInfo?, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(player) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val duration = player?.durationMs?.takeIf { it > 0 }
    val pos = if (player == null) 0L
    else positionAt(player.positionMs, player.updatedAtMs, player.speed, player.playing, now, duration)
    val fraction = if (duration != null) progressFraction(pos, duration) else 0f
    val textColor = Color.White.copy(alpha = 0.85f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(mmss(pos), fontSize = 10.sp, lineHeight = 12.sp, color = textColor, maxLines = 1)
        Box(
            Modifier.weight(1f).padding(horizontal = 6.dp).height(4.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Color.White, CircleShape))
        }
        Text(if (duration != null) mmss(duration) else "", fontSize = 10.sp, lineHeight = 12.sp, color = textColor,
            maxLines = 1, modifier = Modifier.widthIn(min = 0.dp))
    }
}

/** A 22 dp icon-only button: just the icon, a circle only when focused. */
@Composable
private fun IconButton(icon: ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(22.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = playerColors(tint),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(icon, contentDescription = null, modifier = Modifier.size(14.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
        }
    }
}

@Composable
private fun playerColors(content: Color) = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent, focusedContainerColor = Jt.FrostFocused,
    contentColor = content, focusedContentColor = Jt.TextOnFocus,
)

/** ⏮ / ⏸ or ▶ (by state) / ⏭, drawn so they never fall back to colour emoji. */
@Composable
internal fun MediaGlyph(button: MediaButton, playing: Boolean, color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        when (button) {
            MediaButton.PREVIOUS -> {
                bar(color, 0.08f * w, w * 0.14f)
                triangle(color, tipX = w * 0.24f, baseX = w * 0.92f)
            }
            MediaButton.NEXT -> {
                triangle(color, tipX = w * 0.76f, baseX = w * 0.08f)
                bar(color, w * 0.78f, w * 0.14f)
            }
            MediaButton.PLAY_PAUSE -> if (playing) {
                bar(color, w * 0.18f, w * 0.22f)
                bar(color, w * 0.60f, w * 0.22f)
            } else {
                triangle(color, tipX = w * 0.92f, baseX = w * 0.16f)
            }
        }
    }
}

private fun DrawScope.bar(color: Color, x: Float, width: Float) =
    drawRect(color, Offset(x, size.height * 0.1f), Size(width, size.height * 0.8f))

/** A triangle pointing from [baseX] towards [tipX]. */
private fun DrawScope.triangle(color: Color, tipX: Float, baseX: Float) {
    val h = size.height
    drawPath(Path().apply { moveTo(baseX, h * 0.1f); lineTo(tipX, h * 0.5f); lineTo(baseX, h * 0.9f); close() }, color)
}

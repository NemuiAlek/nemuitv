package com.nemuigames.nemuitv.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import com.nemuigames.nemuitv.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

private val FallbackGradient = Brush.verticalGradient(listOf(Color(0xFF0B0F17), Color(0xFF1A2233)))

/**
 * Full-screen, muted, looping video wallpaper. [file] null = dark gradient instead.
 * [poster] (the video's first frame) covers the video until its first frame is actually on screen, then fades out.
 */
@OptIn(UnstableApi::class)
@Composable
fun WallpaperLayer(file: String?, poster: Bitmap?, playing: Boolean, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentOnError = rememberUpdatedState(onError)
    val currentFile = rememberUpdatedState(file)

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
    }
    var videoOnScreen by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { videoOnScreen = true }
            override fun onPlayerError(error: PlaybackException) {
                currentFile.value?.let { currentOnError.value(it) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    // Visible + have a file → (re)load and play. Hidden → stop(), which frees the hardware video decoder
    // so the app in front (Netflix, YouTube…) gets it; a paused player would keep holding it.
    // "Visible" means started, not resumed: stopping at pause would blank the video while the app opens over us.
    LaunchedEffect(file, playing) {
        if (file == null || !playing) {
            player.stop()
            videoOnScreen = false
            if (file == null) player.clearMediaItems()
            return@LaunchedEffect
        }
        val uri = Uri.fromFile(File(file))
        if (player.currentMediaItem?.localConfiguration?.uri != uri || player.playbackState == Player.STATE_IDLE) {
            videoOnScreen = false
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
        player.playWhenReady = true
    }

    // Gradient only when there's no video (painting it behind the video is a wasted full-screen fill).
    Box(if (file == null) modifier.fillMaxSize().background(FallbackGradient) else modifier.fillMaxSize()) {
        if (file != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.wallpaper_player, null) as PlayerView).apply {
                        this.player = player
                    }
                },
            )
            // Not drawn at all once faded (a full-screen image every frame would cost GPU time).
            val posterAlpha by animateFloatAsState(if (videoOnScreen) 0f else 1f, tween(if (videoOnScreen) 300 else 0), label = "poster")
            if (poster != null && posterAlpha > 0f) {
                val img = remember(poster) { poster.asImageBitmap() }
                Image(img, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha })
            }
        }
    }
}

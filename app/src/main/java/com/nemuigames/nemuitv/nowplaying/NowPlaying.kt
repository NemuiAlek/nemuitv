package com.nemuigames.nemuitv.nowplaying

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.nemuigames.nemuitv.alerts.MediaHandle
import com.nemuigames.nemuitv.controlcenter.launchPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the Now Playing tile/banner shows. [title] null → show [appLabel]. [art] = artwork, else the app's icon. */
data class NowPlayingState(
    val pkg: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val art: ImageBitmap?,
    val playing: Boolean,
)

/**
 * Watches the TV's media sessions (any app) and publishes the one to show: music apps only ([isMusicApp]), then
 * [chooseSession]. [anyPlaying] / [isPlaying] still see every app. Needs
 * [NowPlayingListener] to be an enabled notification listener; without it [current] stays null ("Nothing playing").
 * Lives in the app-wide core so it works while an app is in front (the Control Center overlay). Main thread only.
 */
class NowPlaying(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val msm = app.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(app, NowPlayingListener::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<NowPlayingState?>(null)
    val current: StateFlow<NowPlayingState?> = _current.asStateFlow()

    private val _player = MutableStateFlow<PlayerInfo?>(null)
    /** Position / duration / shuffle / repeat of the chosen session; null when [current] is null. */
    val player: StateFlow<PlayerInfo?> = _player.asStateFlow()
    private var lastPlayerLog: Triple<Boolean?, RepeatMode?, Long?>? = null

    /**
     * One watched session. Its metadata / playback state are read from the controller once (here) and then kept up to
     * date from the callbacks' arguments, so [refresh] never makes a binder call.
     */
    private class Watched(
        val controller: MediaController,
        val callback: MediaController.Callback,
        /** Compat view of the same session: shuffle / repeat modes + their setters (the framework API has none). */
        val compat: MediaControllerCompat?,
        val compatCallback: MediaControllerCompat.Callback?,
    ) {
        /** Cached from [compat] (seeded once here, then from its callbacks) so refresh never makes a binder call. -1 = unknown. */
        var shuffleMode: Int = runCatching { compat?.shuffleMode }.getOrNull() ?: PlaybackStateCompat.SHUFFLE_MODE_INVALID
        var repeatMode: Int = runCatching { compat?.repeatMode }.getOrNull() ?: PlaybackStateCompat.REPEAT_MODE_INVALID
        val pkg: String = controller.packageName
        var meta: MediaMetadata? = controller.metadata
        var playback: PlaybackState? = controller.playbackState
        var lastState: Int? = playback?.state
        /**
         * When we last saw its play/pause state change (elapsedRealtime); starts at the session's own update time —
         * clamped to now, since a buggy app may report wall-clock time there and its pause would never expire.
         */
        var lastChangeMs: Long = playback?.lastPositionUpdateTime?.coerceAtMost(SystemClock.elapsedRealtime()) ?: 0L

        fun unregister() {
            controller.unregisterCallback(callback)
            compat?.let { c -> compatCallback?.let { cb -> runCatching { c.unregisterCallback(cb) } } }
        }
    }

    private val watched = mutableMapOf<MediaSession.Token, Watched>()
    private var chosen: MediaController? = null
    private var listening = false
    /** True once [start] got the notification-listener grant; false = sessions are invisible ([anyPlaying] always false). */
    val isListening: Boolean get() = listening
    private var staleTimer: Job? = null

    // Art is scaled once per source bitmap (see ArtKey: keyed on the bitmap object, not the track's text, so new art
    // with unchanged text is picked up). Identical art re-sent in a new metadata object keeps the cached ImageBitmap,
    // so equal states stay equal and the StateFlow quiet. Icons once per app.
    // Same track without a cover keeps the last cover (no flicker to DISPLAY_ICON); see ArtSource.
    private val artSource = ArtSource<Bitmap>()
    private val artCache = SingleSlotCache<ArtKey, ImageBitmap?>(same = { a, b -> a.asAndroidBitmap().sameAs(b.asAndroidBitmap()) })
    private val iconCache = mutableMapOf<String, ImageBitmap?>()
    private val labelCache = mutableMapOf<String, String>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateSessions(controllers.orEmpty())
    }

    /** Start watching (idempotent). Without the listener permission this logs once and leaves [current] null. */
    fun start() {
        if (listening) return
        val sessions = try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            msm.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
            Log.i(TAG, "Now Playing off: notification listener not granted (run scripts/setup.sh)")
            return
        }
        listening = true
        updateSessions(sessions)
        // A paused session drops off after 10 min even if nothing calls back; re-check now and then.
        staleTimer = scope.launch {
            while (isActive) {
                delay(30_000)
                if (_current.value?.playing == false) refresh()
            }
        }
    }

    /** Stop watching and forget everything. */
    fun stop() {
        if (listening) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        staleTimer?.cancel(); staleTimer = null
        updateSessions(emptyList())
    }

    /** Any watched session is PLAYING right now (paused / stopped don't count). */
    val anyPlaying: Boolean get() = watched.values.any { it.playback?.state.isPlaying() }

    /** [pkg] has a session that is PLAYING right now. */
    fun isPlaying(pkg: String): Boolean = watched.values.any { it.pkg == pkg && it.playback?.state.isPlaying() }

    /** First watched session (any app) that is PLAYING right now, as a pause / play handle; null if none. */
    fun playingHandle(): MediaHandle? = watched.values.firstOrNull { it.playback?.state.isPlaying() }?.let { w ->
        object : MediaHandle {
            override val pkg: String = w.pkg
            override val isPlaying: Boolean get() = w.controller.playbackState?.state.isPlaying()
            override fun pause() = w.controller.transportControls.pause()
            override fun play() = w.controller.transportControls.play()
        }
    }

    fun playPause() {
        val c = chosen ?: return
        if (_current.value?.playing == true) c.transportControls.pause() else c.transportControls.play()
    }

    fun setShuffle(on: Boolean) {
        val w = chosenWatched() ?: return
        w.compat?.transportControls?.setShuffleMode(
            if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
    }

    fun cycleRepeat() {
        val w = chosenWatched() ?: return
        val next = (_player.value?.repeat ?: RepeatMode.OFF).next()
        w.compat?.transportControls?.setRepeatMode(when (next) {
            RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
            RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
            RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        })
    }

    private fun chosenWatched(): Watched? = watched.values.firstOrNull { it.controller === chosen }

    fun next() { chosen?.transportControls?.skipToNext() }
    fun previous() { chosen?.transportControls?.skipToPrevious() }

    /** Opens the app that's playing (the overlay host closes the panel first via its own onOpenApp). */
    fun openApp() { _current.value?.pkg?.let { app.launchPackage(it) } }

    private fun updateSessions(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        // Drop sessions that went away.
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { it.unregister() }
        }
        // Watch new ones.
        for (c in controllers) {
            if (c.sessionToken in watched) continue
            val token = c.sessionToken
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    watched[token]?.let { w ->
                        w.playback = state
                        if (state?.state != w.lastState) { w.lastState = state?.state; w.lastChangeMs = SystemClock.elapsedRealtime() }
                    }
                    refresh()
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    watched[token]?.meta = metadata
                    refresh()
                }
                override fun onSessionDestroyed() {
                    watched.remove(token)?.let { it.unregister() }
                    refresh()
                }
            }
            val compat = runCatching { MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(token)) }.getOrNull()
            // Playback state is already handled by the framework callback above; only the modes come from here.
            val compatCallback = compat?.let { c ->
                object : MediaControllerCompat.Callback() {
                    override fun onShuffleModeChanged(shuffleMode: Int) { watched[token]?.shuffleMode = shuffleMode; refresh() }
                    override fun onRepeatModeChanged(repeatMode: Int) { watched[token]?.repeatMode = repeatMode; refresh() }
                    // The compat controller gets its extra binder asynchronously, so the seed in Watched usually reads
                    // INVALID and the *Changed callbacks only fire on a change: re-read once the session is ready.
                    override fun onSessionReady() {
                        watched[token]?.let { w ->
                            w.shuffleMode = runCatching { c.shuffleMode }.getOrNull() ?: PlaybackStateCompat.SHUFFLE_MODE_INVALID
                            w.repeatMode = runCatching { c.repeatMode }.getOrNull() ?: PlaybackStateCompat.REPEAT_MODE_INVALID
                        }
                        refresh()
                    }
                }
            }
            c.registerCallback(callback, main)
            if (compat != null && compatCallback != null) runCatching { compat.registerCallback(compatCallback, main) }
            watched[token] = Watched(c, callback, compat, compatCallback)
        }
        refresh()
    }

    private fun refresh() {
        val infos = watched.values.map { w ->
            val meta = w.meta
            SessionInfo(
                pkg = w.pkg,
                playing = w.playback?.state.isPlaying(),
                lastChangeMs = w.lastChangeMs,
                title = meta?.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = meta?.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                stopped = w.playback?.state.isStopped(),
            ) to w
        }
        val pick = chooseSession(infos.map { it.first }.filter { isMusicApp(it.pkg) }, SystemClock.elapsedRealtime())
        val w = infos.firstOrNull { it.first === pick }?.second
        chosen = w?.controller
        _player.value = w?.let { playerOf(it) }
        // Equal to the last value (same track, same art bitmap) → StateFlow doesn't emit.
        _current.value = if (pick == null || w == null) null else NowPlayingState(
            pkg = pick.pkg,
            appLabel = label(pick.pkg),
            title = pick.title,
            artist = pick.artist,
            art = art(pick, w.meta) ?: icon(pick.pkg),
            playing = pick.playing,
        )
    }

    private fun playerOf(w: Watched): PlayerInfo? {
        val pb = w.playback
        val info = playerInfoOf(
            state = pb?.state,
            position = pb?.position ?: 0L,
            updatedAt = pb?.lastPositionUpdateTime ?: 0L,
            speed = pb?.playbackSpeed ?: 0f,
            actions = pb?.actions ?: 0L,
            durationMs = w.meta?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            shuffleMode = w.shuffleMode,
            repeatMode = w.repeatMode,
        )
        val key = info?.let { Triple(it.shuffle, it.repeat, it.durationMs) }
        if (key == null) lastPlayerLog = null
        else if (key != lastPlayerLog) {
            lastPlayerLog = key
            Log.i(TAG, "player: shuffle=${key.first} repeat=${key.second} duration=${key.third}")
        }
        return info
    }

    private fun art(pick: SessionInfo, meta: MediaMetadata?): ImageBitmap? {
        val src = artSource.choose(
            TrackKey(pick.pkg, pick.title, pick.artist),
            cover = meta?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) },
            icon = meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        )
        return artCache.get(ArtKey(pick.pkg, src)) { src?.let { runCatching { it.scaledDown(ART_MAX_PX).asImageBitmap() }.getOrNull() } }
    }

    private fun icon(pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
        runCatching { app.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    }

    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private companion object {
        const val TAG = "NemuiTV"
        const val ART_MAX_PX = 256
        const val ICON_PX = 128
    }
}

internal fun Int?.isPlaying() = this == PlaybackState.STATE_PLAYING || this == PlaybackState.STATE_BUFFERING ||
    this == PlaybackState.STATE_FAST_FORWARDING || this == PlaybackState.STATE_REWINDING

/** NONE / STOPPED / ERROR (or no state at all): not a session worth showing as paused. */
private fun Int?.isStopped() = this == null || this == PlaybackState.STATE_NONE ||
    this == PlaybackState.STATE_STOPPED || this == PlaybackState.STATE_ERROR

private fun MediaMetadata.text(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { getString(it)?.takeIf { s -> s.isNotBlank() } }

/** Scales so the longer side is at most [maxPx] (keeps aspect); returns the same bitmap if already small. */
private fun Bitmap.scaledDown(maxPx: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxPx) return this
    val f = maxPx.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1), true)
}

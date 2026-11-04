package com.nemuigames.nemuitv.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.nowplaying.debugMusicPkg

/**
 * Debug builds only: injects a fake alert so device tests don't need a laundry cycle or a real ring.
 *
 *   adb shell am broadcast -n com.nemuigames.nemuitv/.alerts.DebugAlertReceiver \
 *       -a com.nemuigames.nemuitv.DEBUG_ALERT --es kind washer|dryer|doorbell|music|nomusic
 *
 * `music` starts a fake playing session from NemuiTV itself (counted as a music app), so the banner + player can be
 * tested on the emulator; `nomusic` ends it.
 * Bypasses the no-replay tracker and the TV-off check; the rest is the real path (camera hold, timers, stack).
 * Manifest receivers run on the main thread, which is AlertCenter's thread.
 */
class DebugAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (val kind = intent.getStringExtra("kind") ?: "") {
            "music" -> startMusic(context.applicationContext)
            "nomusic" -> { session?.release(); session = null }
            else -> context.core.alerts.debugFire(kind)
        }
    }

    private fun startMusic(app: Context) {
        debugMusicPkg = app.packageName
        val s = session ?: MediaSession(app, "debug-music").also { session = it }
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Fake Song For Testing")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Debug Band")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
            .build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or 262144L or
                2097152L)
            .setState(PlaybackState.STATE_PLAYING, 60_000, 1f, SystemClock.elapsedRealtime())
            .build())
        s.isActive = true
    }

    private companion object {
        const val ACTION = "com.nemuigames.nemuitv.DEBUG_ALERT"
        var session: MediaSession? = null
    }
}

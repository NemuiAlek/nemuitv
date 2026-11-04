package com.nemuigames.nemuitv.nowplaying

const val SPOTIFY_PKG = "com.spotify.tv.android"
/** YouTube Music for Android TV; not installed on the Streamer yet (11-02), listed so it just works once it is. */
const val YOUTUBE_MUSIC_PKG = "com.google.android.youtube.tvmusic"

/**
 * Apps Now Playing shows (Control Center, banners, screensaver ◀ ▶). Video apps are left out on purpose: YouTube gives a video title at best, Netflix nothing. "Is anything playing on the box?" (lofi, sleep
 * timers) still looks at every app.
 */
fun isMusicApp(pkg: String): Boolean = pkg == SPOTIFY_PKG || pkg == YOUTUBE_MUSIC_PKG || pkg == debugMusicPkg

/** Debug builds only (DebugAlertReceiver `kind music`): NemuiTV's own fake session counts as music. Never set in release. */
@Volatile var debugMusicPkg: String? = null

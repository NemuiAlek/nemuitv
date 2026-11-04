package com.nemuigames.nemuitv.nowplaying

import android.service.notification.NotificationListenerService
import com.nemuigames.nemuitv.core.core

/**
 * Exists only so Android lets [NowPlaying] read other apps' media sessions (`getActiveSessions` needs an enabled
 * notification listener). Turned on once by `scripts/setup.sh`. Ignores notifications themselves.
 * When Android connects it (e.g. right after the grant), Now Playing (re)starts so it works without an app restart.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        applicationContext.core.nowPlaying.start()
    }
}

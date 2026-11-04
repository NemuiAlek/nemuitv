package com.nemuigames.nemuitv.ambient

import android.util.Log
import com.nemuigames.nemuitv.config.LofiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Starts the Lofi Girl playlist on the TV's Spotify app through Home Assistant (Spotify Connect), in the background:
 * select_source -> 3 s -> volume_set -> play_media, then waits up to 10 s for Spotify's media session to be PLAYING.
 * No wake and no retry: if Spotify isn't running (or HA is offline) it logs and stops. Calls not yet sent are dropped
 * once the screensaver has ended ([dreaming] false); a play call already sent keeps playing. One run at a time.
 * Main thread only. Never logs the HA token (it never sees it).
 */
class Lofi(
    private val callService: (domain: String, service: String, data: JsonObject) -> Boolean,
    private val scope: CoroutineScope,
    private val config: () -> LofiConfig?,
    private val isSpotifyPlaying: () -> Boolean,
    private val dreaming: StateFlow<Boolean>,
    private val delayMs: suspend (Long) -> Unit = ::delay,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    private suspend fun run() {
        val cfg = config() ?: return
        // Each call: dropped if the screensaver ended; stop if HA refused (offline).
        fun send(service: String, data: JsonObject): Boolean {
            if (!dreaming.value) return false
            return callService("media_player", service, data)
        }
        if (!send("select_source", buildJsonObject { put("entity_id", cfg.spotifyEntity); put("source", cfg.source) })) return
        delayMs(SOURCE_WAIT_MS)
        if (!send("volume_set", buildJsonObject { put("entity_id", cfg.spotifyEntity); put("volume_level", cfg.volume) })) return
        val played = send("play_media", buildJsonObject {
            put("entity_id", cfg.spotifyEntity)
            put("media_content_type", "playlist")
            put("media_content_id", cfg.playlist)
        })
        if (!played) return
        var waited = 0L
        while (true) {
            if (isSpotifyPlaying()) { Log.i(TAG, "lofi: started"); return }
            if (waited >= PLAY_TIMEOUT_MS) break
            delayMs(POLL_MS)
            waited += POLL_MS
        }
        Log.i(TAG, "lofi: Spotify not running")
    }

    private companion object {
        const val TAG = "NemuiTV"
        const val SOURCE_WAIT_MS = 3_000L
        const val POLL_MS = 1_000L
        const val PLAY_TIMEOUT_MS = 10_000L
    }
}

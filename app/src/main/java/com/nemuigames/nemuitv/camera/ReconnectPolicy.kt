package com.nemuigames.nemuitv.camera

/** Backoff for camera sessions (Google SDM quota: every connect costs one command). */
class ReconnectPolicy(private val random: () -> Double = { Math.random() }) {
    var failuresInRow: Int = 0
        private set
    private var lastRateLimited = false

    fun onSessionFailed(rateLimited: Boolean) {
        failuresInRow++
        lastRateLimited = rateLimited
    }

    /** A session that reached live and then ended; 60 s or longer counts as healthy. */
    fun onSessionEnded(durationMs: Long) {
        if (durationMs >= HEALTHY_MS) reset()
    }

    fun reset() {
        failuresInRow = 0
        lastRateLimited = false
    }

    fun nextDelayMs(): Long {
        if (failuresInRow == 0) return 0
        val exp = (failuresInRow - 1).coerceAtMost(10)
        val base = minOf(BASE_MS * (1L shl exp), MAX_MS)
        val jittered = (base * (0.8 + 0.4 * random())).toLong()
        return if (lastRateLimited) maxOf(jittered, RATE_LIMIT_MS) else jittered
    }

    private companion object {
        const val BASE_MS = 10_000L
        const val MAX_MS = 120_000L
        const val RATE_LIMIT_MS = 60_000L
        const val HEALTHY_MS = 60_000L
    }
}

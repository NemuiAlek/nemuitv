package com.nemuigames.nemuitv.ha

/** Reconnect waits: 1s, 2s, 4s, 8s, 16s, then 30s forever, until [reset] (after a successful login). */
class Backoff {
    private var attempt = 0
    fun nextDelayMs(): Long = minOf(1_000L shl minOf(attempt++, 5), 30_000L)
    fun reset() { attempt = 0 }
}

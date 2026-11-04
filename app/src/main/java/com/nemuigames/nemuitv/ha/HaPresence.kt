package com.nemuigames.nemuitv.ha

import com.nemuigames.nemuitv.config.TvConfig

/**
 * Home Assistant is optional. With no `ha` block in config.json the status is NOT_CONFIGURED, which is a normal
 * state: no HA error markers, no HA-only controls. HA that was set up and then dropped still shows its problems.
 */
object HaPresence {
    /** Whether HA-backed UI belongs on screen at all. */
    fun showsHaUi(status: HaStatus): Boolean = status != HaStatus.NOT_CONFIGURED

    /** Whether to draw a problem marker (stale weather, rejected token). Never for an HA-less setup. */
    fun showsProblem(status: HaStatus, disconnectedSince: Long?, now: Long): Boolean = when (status) {
        HaStatus.NOT_CONFIGURED -> false
        HaStatus.AUTH_FAILED -> true
        else -> isStale(disconnectedSince, now)
    }
}

/** The Inputs circle needs a configured TV with inputs, and HA to switch them. */
fun inputsAvailable(tv: TvConfig?, status: HaStatus): Boolean =
    tv != null && tv.inputs.isNotEmpty() && HaPresence.showsHaUi(status)

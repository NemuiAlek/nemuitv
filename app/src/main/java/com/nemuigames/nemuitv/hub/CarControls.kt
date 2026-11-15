package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.ha.HaError

/** How long a "controls not connected" block holds before the next press is a real try again. */
const val CAR_BLOCK_MS = 30 * 60_000L
/** Car commands wake the car first, which can take a while. */
const val CAR_CONFIRM_MS = 45_000L

private val NO_KEY_PHRASES = listOf("did not recognize the key", "key is paired", "paired with the vehicle")

/**
 * Tesla's "this car doesn't know the virtual key" failure (the key was never paired from this account).
 * Anything else is false: an unrecognized error shows the normal "Couldn't …" and never grays controls.
 */
fun isNoKeyError(e: HaError): Boolean = NO_KEY_PHRASES.any { e.message.contains(it, ignoreCase = true) }

/** Cabin overheat protection: on (with or without A/C) goes off; off or unknown goes on (`cool`). */
fun nextCopMode(current: String?): String = if (current == "cool" || current == "fan_only") "off" else "cool"

fun carNotConnectedMessage(prefix: String): String =
    "${carDisplayName(prefix)} isn't connected for controls yet. Pair its key in the Tesla app."

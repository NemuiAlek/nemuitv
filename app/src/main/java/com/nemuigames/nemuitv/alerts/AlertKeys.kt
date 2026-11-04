package com.nemuigames.nemuitv.alerts

import android.view.KeyEvent

/**
 * The remote while a doorbell pop-up / expanded view shows: only OK (expand) and Back (close) are taken, acting on
 * key-up; every other key reaches the app. Used by HomeTakeoverService's key filter (over any app) and by
 * HomeActivity when the service is off (in-app fallback).
 *
 * A press is taken whole or not at all: a key-up is consumed only if its key-down was (so an app never sees half a
 * press, e.g. a lone Back-up after the pop-up auto-closed mid-press, or a down without its up when a ring lands
 * mid-press). Main thread only.
 */
class AlertKeys(
    private val capturing: () -> Boolean,
    private val onOk: () -> Unit,
    private val onBack: () -> Unit,
) {
    private val heldDowns = HashSet<Int>()

    /** True = consumed. [canceled] = the up carries FLAG_CANCELED (consumed, not acted on). */
    fun onKey(keyCode: Int, down: Boolean, canceled: Boolean = false): Boolean {
        if (keyCode !in KEYS) return false
        if (down) {
            if (!capturing()) { heldDowns.remove(keyCode); return false } // also drops a stale entry (lost up)
            heldDowns += keyCode // repeats of a held key land here too
            return true
        }
        if (!heldDowns.remove(keyCode)) return false // its down went to the app: so does the up
        if (!canceled && capturing()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) onBack() else onOk()
        }
        return true
    }

    companion object {
        /** HomeTakeoverService's gate: only while the overlay window can show the doorbell (else the app keeps its keys). */
        fun overlayCaptures(overlayOn: Boolean, doorbellShows: Boolean) = overlayOn && doorbellShows

        /** HomeActivity's gate (in-app fallback): only while the overlay is off, so never both. */
        fun inAppCaptures(overlayOn: Boolean, doorbellShows: Boolean) = !overlayOn && doorbellShows

        private val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        )
    }
}

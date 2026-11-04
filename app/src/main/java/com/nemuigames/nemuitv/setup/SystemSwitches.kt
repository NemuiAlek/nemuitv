package com.nemuigames.nemuitv.setup

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.nemuigames.nemuitv.ambient.isOurDream
import kotlinx.coroutines.flow.first

/**
 * Flips NemuiTV's own system switches (Home-button service, Now Playing listener, screensaver) by writing Android's
 * secure settings. Needs WRITE_SECURE_SETTINGS (granted once from a computer, see the README). Never touches
 * Google's home or anyone else's entries: lists are edited with [SecureLists].
 */
class SystemSwitches(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val pkg get() = context.packageName

    private val takeover get() = "$pkg/$pkg.takeover.HomeTakeoverService"
    private val takeoverShort get() = "$pkg/.takeover.HomeTakeoverService"
    private val listener get() = "$pkg/$pkg.nowplaying.NowPlayingListener"
    private val listenerShort get() = "$pkg/.nowplaying.NowPlayingListener"
    private val dream get() = "$pkg/.ambient.AmbientDream"

    fun secureGranted(): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun takeoverOn(): Boolean = SecureLists.contains(secure(ACCESSIBILITY_LIST), takeover, takeoverShort)
    fun nowPlayingOn(): Boolean = SecureLists.contains(secure(LISTENER_LIST), listener, listenerShort)
    fun screensaverOurs(): Boolean = isOurDream(secure(SCREENSAVER), pkg)

    /** On: our service joins the list and accessibility is switched on. Off: only our entry leaves. */
    fun setTakeover(on: Boolean): Boolean = guarded("takeover") {
        val cur = secure(ACCESSIBILITY_LIST)
        if (on) {
            put(ACCESSIBILITY_LIST, SecureLists.add(cur, takeover, takeoverShort))
            putInt(ACCESSIBILITY_ENABLED, 1)
        } else {
            val new = SecureLists.remove(cur, takeover, takeoverShort)
            put(ACCESSIBILITY_LIST, new)
            if (new.isEmpty()) putInt(ACCESSIBILITY_ENABLED, 0) // nothing else relies on it
        }
    }

    /** Writes the list, but Android only binds/unbinds the listener after a reboot; the Setup page shows this row status-only. */
    fun setNowPlaying(on: Boolean): Boolean = guarded("now playing") {
        val cur = secure(LISTENER_LIST)
        put(LISTENER_LIST, if (on) SecureLists.add(cur, listener, listenerShort) else SecureLists.remove(cur, listener, listenerShort))
    }

    /** On: NemuiTV becomes the screensaver (also when the TV sleeps), after remembering what was there. Off: put that back. */
    suspend fun setScreensaver(on: Boolean, store: SetupStore): Boolean {
        if (on) {
            val cur = secure(SCREENSAVER)
            if (cur != null && cur != "null" && !isOurDream(cur, pkg)) store.setPreviousScreensaver(cur)
            return guarded("screensaver") {
                put(SCREENSAVER, dream)
                put(SCREENSAVER_DEFAULT, dream)
                putInt(SCREENSAVER_ENABLED, 1)
                putInt(SCREENSAVER_ON_SLEEP, 1)
            }
        }
        val back = store.previousScreensaver.first()?.takeIf { it.isNotBlank() } ?: BACKDROP
        return guarded("screensaver") {
            put(SCREENSAVER, back)
            put(SCREENSAVER_DEFAULT, back)
        }
    }

    private fun secure(key: String): String? = Settings.Secure.getString(resolver, key)
    private fun put(key: String, value: String) { Settings.Secure.putString(resolver, key, value) }
    private fun putInt(key: String, value: Int) { Settings.Secure.putInt(resolver, key, value) }

    /** Runs a write; a refusal (permission missing or revoked) is logged and reported as false, never thrown. */
    private fun guarded(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: SecurityException) {
        Log.w("NemuiTV", "setup: can't write $what settings (no WRITE_SECURE_SETTINGS)", e); false
    }

    private companion object {
        const val ACCESSIBILITY_LIST = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
        const val LISTENER_LIST = "enabled_notification_listeners"
        const val SCREENSAVER = "screensaver_components"
        const val SCREENSAVER_DEFAULT = "screensaver_default_component"
        const val SCREENSAVER_ENABLED = "screensaver_enabled"
        const val SCREENSAVER_ON_SLEEP = "screensaver_activate_on_sleep"
        const val BACKDROP = "com.google.android.apps.tv.dreamx/.service.Backdrop"
    }
}

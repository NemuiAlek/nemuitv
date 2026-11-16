package com.nemuigames.nemuitv.takeover

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.nemuigames.nemuitv.HomeActivity
import com.nemuigames.nemuitv.alerts.AlertKeys
import com.nemuigames.nemuitv.alerts.AlertOverlay
import com.nemuigames.nemuitv.controlcenter.CcPage
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.takeover.HomeKeyDecider.Action

/**
 * Makes the remote's Home button open NemuiTV on Google TV, where Google's launcher can't be disabled
 * (that package also runs Wi-Fi services). Tap Home = NemuiTV, hold Home = NemuiTV's Control Center on top of
 * whatever is showing ([ControlCenterOverlay]; Back closes it); while NemuiTV's screensaver runs, Home ends it and goes
 * Home (no hold),
 * and whenever Google's home screen appears anyway (Back out of an app, boot) NemuiTV replaces it.
 * Turned on by scripts/setup.sh (or the Setup page). If NemuiTV crashes, Android turns this off by itself,
 * and Home goes back to Google's launcher.
 */
class HomeTakeoverService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val decider = HomeKeyDecider()
    private var lastLaunchMs = Long.MIN_VALUE / 2
    private val holdCheck = Runnable { perform(decider.onHoldTimeout()) }
    private lateinit var overlay: ControlCenterOverlay
    private var alertOverlay: AlertOverlay? = null
    // OK / Back while a doorbell pop-up or expanded view shows (over any app); everything else reaches the app.
    // Only while the overlay window is on: if it couldn't be added, the alert shows in NemuiTV only (HomeActivity
    // takes the keys there), so an app in front must keep OK/Back.
    private val alertKeys by lazy {
        AlertKeys(
            { AlertKeys.overlayCaptures(core.alertOverlayOn.value, core.alerts.capturesKeys.value) },
            { core.alerts.expand() }, { core.alerts.dismiss() },
        )
    }

    // The overlay window sits above the screensaver: close it when one starts, or it would burn in a still panel.
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val open = ::overlay.isInitialized && overlay.isShowing
            Log.i(TAG, "screensaver started (Control Center overlay open=$open)")
            if (open) overlay.hide()
        }
    }
    private var dreamReceiverOn = false
    private var ringJob: kotlinx.coroutines.Job? = null
    private var setupJob: kotlinx.coroutines.Job? = null
    private var updateJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        overlay = ControlCenterOverlay(this)
        // A doorbell ring closes the Control Center over apps, so the pop-up (and its OK / Back) is seen.
        ringJob?.cancel()
        ringJob = core.closeCcRequests.onEach { if (overlay.isShowing) overlay.hide() }.launchIn(core.scope)
        core.takeoverRunning.value = true
        setupJob?.cancel()
        setupJob = core.setupOverlayRequests.onEach { overlay.show(CcPage.SETUP) }.launchIn(core.scope)
        updateJob?.cancel()
        updateJob = core.update.reopenRequests.onEach { overlay.show(CcPage.UPDATE) }.launchIn(core.scope)
        if (alertOverlay == null) alertOverlay = AlertOverlay(this).also { it.attach() }
        if (!dreamReceiverOn) {
            ContextCompat.registerReceiver(this, dreamReceiver, IntentFilter(Intent.ACTION_DREAMING_STARTED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            dreamReceiverOn = true
        }
        Log.i(TAG, "Home takeover on")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The doorbell wins while it shows: OK expands, Back closes (whole presses, acting on release), before the
        // Control Center's own Back handling below.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val canceled = event.flags and KeyEvent.FLAG_CANCELED != 0
            if (alertKeys.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, canceled)) return true
        }
        // Back goes to the overlay (on release, so the app underneath never sees half a press): a sub-page / the QR
        // closes first, then the panel.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && ::overlay.isInitialized && overlay.isShowing) {
            if (event.action == KeyEvent.ACTION_UP) overlay.back()
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val r = when (event.action) {
            KeyEvent.ACTION_DOWN -> decider.onDown(event.repeatCount, dreaming = core.dreaming.value)
            KeyEvent.ACTION_UP -> decider.onUp()
            else -> return false
        }
        perform(r.action)
        return r.consume
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (GoogleHomeWatch.shouldReclaim(event.packageName, event.className, SystemClock.uptimeMillis(), lastLaunchMs)) {
            Log.i(TAG, "Google's home appeared; showing NemuiTV")
            openNemuiTv()
        }
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.SCHEDULE_HOLD_CHECK -> { main.removeCallbacks(holdCheck); main.postDelayed(holdCheck, HomeKeyDecider.HOLD_MS) }
            Action.OPEN_HOME -> {
                main.removeCallbacks(holdCheck)
                // Starting an activity doesn't end a screensaver (it opens behind it): end ours first.
                if (core.dreaming.value) { Log.i(TAG, "Home: ending the screensaver"); core.requestDreamExit() }
                switchTvHere(); overlay.hide(); openNemuiTv()
            }
            Action.OPEN_CONTROL_CENTER -> { switchTvHere(); overlay.show() }
        }
    }

    /** On another input (PS5…) the TV wouldn't show us: ask HA to switch back (no CEC from apps). */
    private fun switchTvHere() {
        runCatching { if (core.tv.switchToThisDevice()) Log.i(TAG, "Home: switching the TV back to this box") }
            .onFailure { Log.w(TAG, "couldn't switch the TV input", it) }
    }

    private fun openNemuiTv() {
        // E.g. the panel was open over an app that then exited to Google's home: don't leave it on top of NemuiTV.
        if (::overlay.isInitialized) overlay.hide()
        lastLaunchMs = SystemClock.uptimeMillis()
        val intent = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open NemuiTV", it) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ringJob?.cancel(); ringJob = null
        setupJob?.cancel(); setupJob = null
        updateJob?.cancel(); updateJob = null
        core.takeoverRunning.value = false
        if (dreamReceiverOn) { runCatching { unregisterReceiver(dreamReceiver) }; dreamReceiverOn = false }
        if (::overlay.isInitialized) overlay.hide()
        alertOverlay?.detach(); alertOverlay = null
        super.onDestroy()
    }

    companion object { private const val TAG = "NemuiTV" }
}

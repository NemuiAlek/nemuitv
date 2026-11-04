package com.nemuigames.nemuitv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nemuigames.nemuitv.alerts.AlertKeys
import com.nemuigames.nemuitv.alerts.AlertStack
import com.nemuigames.nemuitv.ambient.homeCoverOnStart
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.home.HomeScreen
import com.nemuigames.nemuitv.home.HomeViewModel

/** The launcher's single activity. Android shows this when Home is pressed. */
class HomeActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    // True when we've been fully hidden (an app was opened, or the screen went off) since the last time we showed.
    // Opening the tray or Control Center never hides the activity, so those don't count as "coming back Home".
    private var stoppedSinceLastStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Back on the bare home screen does nothing (a launcher has nowhere to go back to).
            BackHandler { }
            Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                HomeScreen(vm)
                AlertLayerFallback()
            }
        }
    }

    /**
     * In-app fallback: only while the accessibility overlay isn't attached (Home takeover service off), the alert
     * stack is drawn inside NemuiTV itself (home + hub). Never both: with the service on, the overlay draws over us.
     */
    @Composable
    private fun AlertLayerFallback() {
        val overlayOn by core.alertOverlayOn.collectAsState()
        if (overlayOn) return
        val homeInFront by core.homeTopRowInFront.collectAsState()
        val ccOpen by core.ccOpen.collectAsState()
        AlertStack(core.alerts, cameras, homeInFront, ccOpen)
    }

    private val cameras = { core.cameras }

    // The fallback's OK / Back (with the service on, its key filter takes them before they get here).
    private val alertKeys by lazy {
        AlertKeys({ AlertKeys.inAppCaptures(core.alertOverlayOn.value, core.alerts.capturesKeys.value) }, { core.alerts.expand() }, { core.alerts.dismiss() })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val canceled = event.flags and KeyEvent.FLAG_CANCELED != 0
            if (alertKeys.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, canceled)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        core.setHomeStarted(true)
        // Back from the screensaver: start under black, lifted once the wallpaper is up (hides the video switch).
        if (homeCoverOnStart(core.dreaming.value, core.dreamEndedAtMs, SystemClock.elapsedRealtime())) vm.coverAfterDream()
        if (stoppedSinceLastStart) {
            stoppedSinceLastStart = false
            vm.onReturnedHome() // next wallpaper + re-read config.json if it changed
        }
    }

    // Home pressed while NemuiTV is already in front (singleTask delivers it here, not to onStart).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onHomePressed()
    }

    // For the screensaver's "was Home in front?" (see wasHomeBeforeDream): the dream pauses us as it starts.
    override fun onResume() {
        super.onResume()
        core.homeResumed = true
        core.reapplyTimers()
    }

    override fun onPause() {
        super.onPause()
        core.homeResumed = false
        core.wallpaperAtHomePause = core.currentWallpaper.value // onStop may advance it; the dream starts on this one
        core.homePausedAtMs = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        core.setHomeStarted(false)
        core.stopCamerasNow() // another app / screensaver / TV off: no hidden decoders competing, whatever the hub did
        stoppedSinceLastStart = true
        vm.onLeftHome() // next wallpaper picked while hidden, so its poster is ready on return
    }
}

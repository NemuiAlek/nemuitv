package com.nemuigames.nemuitv.alerts

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nemuigames.nemuitv.camera.CameraBank
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.ui.ComposeHostOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The alerts drawn over everything (other apps, NemuiTV, the screensaver) from [HomeTakeoverService]'s accessibility
 * overlay window: full-screen, **not focusable and not touchable**, so the remote stays with the app (OK/Back are
 * taken by the service's key filter via [AlertKeys], only while a doorbell shows).
 *
 * [attach] starts watching [AlertCenter.ui]; the window itself exists only while something shows (a permanent
 * transparent full-screen layer would be one more layer for the compositor over every video) and is removed when the
 * stack empties. [detach] removes it for good. Main thread.
 */
class AlertOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val alerts get() = service.core.alerts
    private val cameras: () -> CameraBank = { service.core.cameras } // only called when a doorbell with a camera shows
    private var scope: CoroutineScope? = null
    private var watch: Job? = null
    private var view: ComposeView? = null
    private var owner: ComposeHostOwner? = null

    fun attach() {
        if (scope != null) return
        val s = MainScope().also { scope = it }
        service.core.alertOverlayOn.value = true // HomeActivity's in-app fallback steps aside
        watch = s.launch {
            alerts.ui.map { it.doorbell != null || it.banners.isNotEmpty() }.distinctUntilChanged()
                .collect { showing -> if (showing) addWindow() else removeWindow() }
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null; watch = null
        removeWindow()
        service.core.alertOverlayOn.value = false
    }

    private fun addWindow() {
        if (view != null) return
        val o = ComposeHostOwner().also { it.start() }
        val v = ComposeView(service).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            // Not named Content(): inside ComposeView.apply that resolves to AbstractComposeView.Content() (M4a lesson).
            setContent { AlertOverlayRoot() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(v, lp) }.onFailure {
            Log.w(TAG, "alerts: overlay window couldn't be added; alerts show in NemuiTV only", it)
            o.stop()
            service.core.alertOverlayOn.value = false
            return
        }
        view = v; owner = o
        service.core.alertOverlayOn.value = true
    }

    // Removing the view disposes the composition: every doorbell renderer's onRelease removes its camera sink.
    private fun removeWindow() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null; owner = null
    }

    @androidx.compose.runtime.Composable
    private fun AlertOverlayRoot() {
        val home by service.core.homeTopRowInFront.collectAsState()
        val ccOpen by service.core.ccOpen.collectAsState()
        AlertStack(alerts, cameras, homeInFront = home, ccOpen = ccOpen)
    }

    private companion object { const val TAG = "NemuiTV" }
}

package com.nemuigames.nemuitv.takeover

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.controlcenter.ControlCenter
import com.nemuigames.nemuitv.controlcenter.CcPage
import com.nemuigames.nemuitv.controlcenter.ControlCenterState
import com.nemuigames.nemuitv.controlcenter.launchPackage
import com.nemuigames.nemuitv.ui.ComposeHostOwner

/**
 * NemuiTV's Control Center drawn on top of whatever app is showing (hold Home). An accessibility service may add
 * this kind of window without any extra permission. Same panel and position as the [⊜] button's version.
 * Back / Home are handled by [HomeTakeoverService]'s key filter (Back → [back]); the arrows and OK go to the panel.
 */
class ControlCenterOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: ComposeHostOwner? = null

    private var state = ControlCenterState()

    val isShowing get() = view != null

    fun show(startAt: CcPage = CcPage.MAIN) {
        if (view != null) return
        state = ControlCenterState() // opens on the main page, unless asked for a sub-page (first-launch Setup)
        if (startAt != CcPage.MAIN) state.startAt(startAt)
        val homeTopRow = service.core.homeTopRowInFront.value
        val o = ComposeHostOwner().also { it.start() }
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                ControlCenter(
                    core = service.core,
                    onClose = ::hide,
                    onOpenApp = { pkg -> hide(); service.launchPackage(pkg) },
                    state = state,
                    // Held Home on NemuiTV's home screen: same in-place look as opening it from [⊜] there.
                    overHomeTopRow = homeTopRow,
                )
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // focusable: the remote's arrows + OK go to the panel
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(v, lp) }.onFailure { o.stop(); return }
        view = v; owner = o
        service.core.ccOverlayShowing.value = true
    }

    /** Back (from the service's key filter): sub-page → main page, main page → close. */
    fun back() { if (state.back()) hide() }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null; owner = null
        service.core.ccOverlayShowing.value = false
    }
}

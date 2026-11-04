package com.nemuigames.nemuitv.ambient

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nemuigames.nemuitv.core.core
import com.nemuigames.nemuitv.ui.ComposeHostOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * NemuiTV as the TV's screensaver: Android starts it after "Ambient after" (system screen_off_timeout). Interactive so it
 * can handle keys itself ([ambientKey]): ◀ ▶ change the song while music plays, any other key ends it (no key is passed
 * on). Home ends it via [com.nemuigames.nemuitv.core.NemuiCore.requestDreamExit]. Shows [AmbientScene]. Made the
 * screensaver by the Setup page's Screensaver switch.
 *
 * Fades (see AmbientTransitions.kt): the window starts translucent so the scene can fade Home out before switching to
 * its own wallpaper; every exit goes through [wakeUp] → [onWakeUp], which fades the scene to black, then finishes.
 */
class AmbientDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: ComposeHostOwner? = null
    private var exitWatch: Job? = null
    private var fade: AmbientFade? = null
    private var finished = false
    private val finishNow = Runnable { if (!finished) { finished = true; finish() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // keys come to dispatchKeyEvent (below), not a non-interactive wake
        isFullscreen = true
        core.dreamEndedAtMs = null
        // Picked now, before Home's onStop can advance its playlist (cosmetic: ambient continues the same video).
        val startAt = ambientStartWallpaper(core.homeResumed, core.homePausedAtMs, SystemClock.elapsedRealtime(),
            core.wallpaperAtHomePause, core.currentWallpaper.value)
        val translucent = startTranslucent()
        Log.i(TAG, "ambient: translucent start=$translucent")
        val f = AmbientFade(translucentStart = translucent, onOpaque = ::becomeOpaque)
        val o = ComposeHostOwner().also { it.start() }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { AmbientScene(core, startAt, f) }
        }
        setContentView(v)
        view = v; owner = o; fade = f
        // Home pressed (HomeTakeoverService): end the screensaver (with the fade); the service then opens NemuiTV.
        exitWatch = core.dreamExitRequests.onEach { Log.i(TAG, "ambient: Home pressed, waking"); wakeUp() }
            .launchIn(core.scope)
    }

    /**
     * Makes the dream's window (the DreamActivity's) see-through so Home stays visible under the scene's first black
     * fade. False (stay opaque, start from black) if Android doesn't allow it.
     */
    private fun startTranslucent(): Boolean = runCatching {
        val activity = window.context as? Activity ?: return false
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setWindowFormat(PixelFormat.TRANSLUCENT)
        activity.setTranslucent(true).also { if (!it) setOpaqueWindow() }
    }.onFailure { Log.w(TAG, "ambient: translucent start failed", it); setOpaqueWindow() }.getOrDefault(false)

    /** Home is faded out under black: go opaque so Android stops what's underneath (Home frees its video decoder). */
    private fun becomeOpaque() {
        runCatching { (window.context as? Activity)?.setTranslucent(false) }
            .onFailure { Log.w(TAG, "ambient: couldn't turn opaque", it) }
        setOpaqueWindow()
    }

    private fun setOpaqueWindow() {
        runCatching {
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setWindowFormat(PixelFormat.OPAQUE)
        }
    }

    // The DreamService is the window's callback and doesn't pass attribute changes on to the window manager
    // (dumpsys window showed the dream window still opaque after Window.setFormat), so push them ourselves.
    private fun setWindowFormat(format: Int) {
        window.setFormat(format)
        val decor = window.decorView
        if (decor.isAttachedToWindow) windowManager.updateViewLayout(decor, window.attributes)
    }

    // Every key is consumed here (never reaches the Compose view or what's underneath).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playing = core.nowPlaying.current.value?.playing == true
        when (ambientKey(event.keyCode, event.repeatCount, event.action == KeyEvent.ACTION_DOWN, playing)) {
            AmbientKeyAction.PREVIOUS -> { Log.i(TAG, "ambient: ◀ previous song"); core.nowPlaying.previous() }
            AmbientKeyAction.NEXT -> { Log.i(TAG, "ambient: ▶ next song"); core.nowPlaying.next() }
            AmbientKeyAction.IGNORE -> Unit
            AmbientKeyAction.WAKE -> wakeUp()
        }
        return true
    }

    /**
     * Every wake lands here (Android's, a key's [wakeUp], Home's): fade the scene to black, then finish. Android waits
     * ~5 s for a waking dream before it ends it itself.
     */
    override fun onWakeUp() {
        val f = fade
        if (f == null) { main.post(finishNow); return } // no scene to fade
        if (f.exiting) return // already fading out; its finish is posted
        core.dreamEndedAtMs = SystemClock.elapsedRealtime()
        f.exiting = true
        main.postDelayed(finishNow, AMBIENT_EXIT_FADE_MS + 50L)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        core.setDreaming(true)
        val wasHome = wasHomeBeforeDream(core.homeResumed, core.homePausedAtMs, SystemClock.elapsedRealtime())
        core.onAmbientStarted(wasHome)
    }

    override fun onDreamingStopped() {
        if (core.dreamEndedAtMs == null) core.dreamEndedAtMs = SystemClock.elapsedRealtime()
        core.setDreaming(false)
        Log.i(TAG, "ambient: stopped")
        super.onDreamingStopped()
    }

    // Dispose the scene first: that releases the wallpaper player (and its hardware decoder) right away, so the app
    // coming back to the front gets it.
    override fun onDetachedFromWindow() {
        main.removeCallbacks(finishNow)
        exitWatch?.cancel(); exitWatch = null
        view?.disposeComposition()
        owner?.stop()
        view = null; owner = null; fade = null
        if (core.dreamEndedAtMs == null) core.dreamEndedAtMs = SystemClock.elapsedRealtime()
        core.setDreaming(false) // in case the dream was torn down without onDreamingStopped
        super.onDetachedFromWindow()
    }

    private companion object { const val TAG = "NemuiTV" }
}

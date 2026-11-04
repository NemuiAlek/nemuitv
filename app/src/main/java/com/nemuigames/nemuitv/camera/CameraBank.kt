package com.nemuigames.nemuitv.camera

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** How long hidden feeds keep running (rail-away, Back): coming back inside it reuses the live sessions. */
const val CAMERA_GRACE_MS = 60_000L

/**
 * The hub's live cameras: one [CameraFeed] per camera, one shared EGL context + PeerConnectionFactory (both created
 * on first use, so unit tests never touch native code), the renderers showing each camera and each video's aspect.
 *
 * [setVisible] drives the hub's lifecycle (with [stopNow] to stop at once and [prewarm] to pre-create native state);
 * [acquireAlertFeed]/[releaseAlertFeed] are a second holder (the doorbell pop-up, over other apps) that the hub's stops
 * leave running. A feed stops only when neither the hub nor the alert holds it. Everything except [sinksFor]/[onVideoSize] (which the runner may
 * call from WebRTC threads) must be called on [scope]'s dispatcher, which must be Main.
 */
class CameraBank(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val runnerFactory: (CameraBank) -> SessionRunner,
) {
    private val eglLazy = lazy { EglBase.create() }
    private val egl: EglBase by eglLazy

    /** Shared by all renderers and the hardware decoder. */
    val eglContext: EglBase.Context get() = egl.eglBaseContext

    private var factory: PeerConnectionFactory? = null
    private var released = false

    // Sessions currently inside run(): release() waits for 0 so no peer connection outlives the factory.
    private val activeSessions = MutableStateFlow(0)
    private val runner: SessionRunner by lazy {
        val inner = runnerFactory(this)
        object : SessionRunner {
            override suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd {
                activeSessions.update { it + 1 }
                try {
                    return inner.run(entityId, onFirstFrame)
                } finally {
                    activeSessions.update { it - 1 } // after the inner runner's own teardown
                }
            }
        }
    }

    private val feeds = LinkedHashMap<String, CameraFeed>()
    private val aspects = ConcurrentHashMap<String, MutableStateFlow<Float>>()
    // Fan-out: every renderer showing a camera (hub tile + doorbell pop-up at once). Written on Main, read per frame
    // on the decoder thread; copy-on-write sets so the reader never sees a half-made change.
    private val sinks = ConcurrentHashMap<String, CopyOnWriteArraySet<VideoSink>>()
    private var pendingStart: Job? = null
    private var pendingStop: Job? = null

    // Cameras the hub holds: requested visible, or hidden but inside the grace. Cleared at grace end / stopNow.
    private var hubHeld: Set<String> = emptySet()

    // Cameras the doorbell alert holds (a set: acquire is idempotent, one doorbell).
    private val alertHeld = mutableSetOf<String>()

    /** One per camera, created on first ask. */
    fun feed(entityId: String): CameraFeed = feeds.getOrPut(entityId) { CameraFeed(entityId, runner, scope, clock, wallClock = wallClock) }

    /** width / height of the camera's video; 16:9 until a frame reports its size. Kept across sessions. */
    fun videoAspect(entityId: String): StateFlow<Float> = aspectFlow(entityId).asStateFlow()

    private fun aspectFlow(entityId: String) = aspects.getOrPut(entityId) { MutableStateFlow(DEFAULT_ASPECT) }

    /** The runner reports the decoded (rotated) frame size; any thread. */
    fun onVideoSize(entityId: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        aspectFlow(entityId).value = width.toFloat() / height
    }

    /**
     * A renderer starts showing [entityId]: it gets every frame from now on, alongside any other renderer of the same
     * camera (the hub tile and the doorbell pop-up can both show the doorbell). Idempotent. Main thread.
     */
    fun addSink(entityId: String, sink: VideoSink) {
        sinks.getOrPut(entityId) { CopyOnWriteArraySet() }.add(sink)
    }

    /** That renderer stops (its view is going away); the camera's other renderers keep their frames. Safe if absent. */
    fun removeSink(entityId: String, sink: VideoSink) {
        val set = sinks[entityId] ?: return
        set.remove(sink)
        if (set.isEmpty()) sinks.remove(entityId, set)
    }

    /** Where frames for [entityId] go right now (possibly none); any thread (looked up per frame by the runner). */
    fun sinksFor(entityId: String): Collection<VideoSink> = sinks[entityId] ?: emptySet()

    /**
     * Shared factory, created on first session (main thread). Throws after [release] — the runner turns that into a
     * failed session, and nothing can start one then anyway.
     */
    fun peerConnectionFactory(context: Context): PeerConnectionFactory {
        check(!released) { "CameraBank released" }
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(HardwareVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /**
     * Creates the PeerConnectionFactory + EGL now (idempotent, main thread) so the first camera session doesn't pay
     * for it. Starts no session: no HA call, no Google command.
     */
    fun prewarm(context: Context) {
        if (released) return
        peerConnectionFactory(context)
    }

    /**
     * true: cancel a pending [CAMERA_GRACE_MS] stop; after 500 ms, start [cameras] staggered 1 s apart (feeds already
     * running keep running) and stop any other feed. false: cancel a pending start and stop every feed after
     * [CAMERA_GRACE_MS] (feeds still in their stagger delay stop now) (coming back sooner keeps them). A true->false inside the 500 ms starts nothing.
     */
    fun setVisible(cameras: List<String>, visible: Boolean) {
        pendingStart?.cancel()
        pendingStart = null
        pendingStop?.cancel()
        pendingStop = null
        if (released) return
        if (!visible) {
            hubFeeds().forEach { it.stopIfNotStarted() } // never start a session nobody sees
            if (feeds.values.all { it.state.value == FeedState.Idle }) {
                hubHeld = emptySet()
                return
            }
            pendingStop = scope.launch {
                delay(CAMERA_GRACE_MS)
                pendingStop = null
                hubHeld = emptySet()
                hubFeeds().forEach { it.stop() }
            }
            return
        }
        hubHeld = hubHeld + cameras // the old ones until the debounce replaces them
        pendingStart = scope.launch {
            delay(DEBOUNCE_MS)
            hubHeld = cameras.toSet()
            hubFeeds().filter { it.entityId !in cameras }.forEach { it.stop() }
            cameras.forEachIndexed { i, id -> feed(id).start(i * STAGGER_MS) }
        }
    }

    /**
     * Cancels any pending start/stop and stops every hub feed at once (another app in front, screensaver, TV off).
     * A feed the alert holds keeps running: the doorbell pop-up shows over other apps, exactly when this fires.
     */
    fun stopNow() {
        pendingStart?.cancel()
        pendingStart = null
        pendingStop?.cancel()
        pendingStop = null
        hubHeld = emptySet()
        hubFeeds().forEach { it.stop() }
    }

    /**
     * The doorbell alert holds [entityId]: starts it now (no debounce, no stagger; the 429 deadline still applies),
     * or reuses the feed if it is already connecting/live/reconnecting. Idempotent.
     */
    fun acquireAlertFeed(entityId: String) {
        if (released) return
        alertHeld += entityId
        val f = feed(entityId)
        f.stopIfNotStarted() // a hub feed still in its stagger delay: start it now instead
        f.start(0)
    }

    /** Drops the alert hold; stops the feed at once unless the hub holds it (visible or in grace). Safe when not held. */
    fun releaseAlertFeed(entityId: String) {
        if (!alertHeld.remove(entityId)) return
        if (entityId !in hubHeld) feeds[entityId]?.stop()
    }

    private fun hubFeeds(): List<CameraFeed> = feeds.values.filter { it.entityId !in alertHeld }

    /** App shutdown: stops all feeds (alert-held too), then (once every session has torn down) disposes the factory and EGL. */
    fun release() {
        if (released) return
        stopNow()
        alertHeld.clear()
        feeds.values.forEach { it.stop() }
        released = true
        sinks.clear()
        scope.launch {
            activeSessions.first { it == 0 }
            factory?.dispose()
            factory = null
            if (eglLazy.isInitialized()) egl.release()
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val STAGGER_MS = 1_000L
        const val DEFAULT_ASPECT = 16f / 9f
    }
}

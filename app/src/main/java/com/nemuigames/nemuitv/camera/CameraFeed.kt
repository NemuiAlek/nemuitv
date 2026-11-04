package com.nemuigames.nemuitv.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SessionEnd {
    /** Before the first frame: error, timeout, subscription closed. */
    data class Failed(val code: String?, val message: String?) : SessionEnd

    /** Was live, then ended. */
    data class Dropped(val liveMs: Long) : SessionEnd
}

interface SessionRunner {
    /**
     * Runs one session until it ends; calls onFirstFrame once video renders. Cancellation = clean teardown.
     * onFirstFrame may be called from any thread.
     */
    suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd
}

sealed interface FeedState {
    data object Idle : FeedState
    data object Connecting : FeedState
    data object Live : FeedState

    /** showLastSeen = three or more failures in a row. */
    data class Reconnecting(val lastFrameAtMs: Long?, val showLastSeen: Boolean) : FeedState
}

class CameraFeed(
    val entityId: String,
    private val runner: SessionRunner,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /** Epoch ms, used ONLY to record lastFrameAtMs for display; [clock] stays monotonic for all timing. */
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<FeedState>(FeedState.Idle)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastFrameAtMs: Long? = null
    private var sessionBegun = false // runner.run has been entered since start()

    // Google's 429 deadline outlives stop()/start(): every (re)start still waits it out.
    private var rateLimitedUntilMs = 0L

    /** Staggered start; no-op if already running. start/stop must be called from the scope's (main) dispatcher. */
    fun start(startDelayMs: Long) {
        if (job?.isActive == true) return
        sessionBegun = false
        job = scope.launch {
            val self = coroutineContext[Job]!!
            _state.value = waitingState()
            delay(startDelayMs)
            delay(maxOf(0L, rateLimitedUntilMs - clock()))
            while (true) {
                _state.value = waitingState()
                sessionBegun = true
                val end = try {
                    runner.run(entityId) {
                        // May arrive on a WebRTC/renderer thread: hop onto the scope to serialize with stop().
                        scope.launch {
                            if (self.isActive) {
                                lastFrameAtMs = wallClock()
                                _state.value = FeedState.Live
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SessionEnd.Failed(null, e.message ?: e::class.simpleName)
                }
                ensureActive()
                when (end) {
                    is SessionEnd.Failed -> {
                        val limited = isRateLimited(end.code, end.message)
                        if (limited) rateLimitedUntilMs = clock() + RATE_LIMIT_MS
                        policy.onSessionFailed(limited)
                    }
                    is SessionEnd.Dropped -> {
                        lastFrameAtMs = wallClock()
                        policy.onSessionEnded(end.liveMs)
                        if (end.liveMs < HEALTHY_MS) policy.onSessionFailed(rateLimited = false)
                    }
                }
                _state.value = waitingState()
                delay(policy.nextDelayMs())
            }
        }
    }

    /** Stops a feed that is still in its start delay (no session yet); leaves one that has connected/been live. */
    fun stopIfNotStarted() {
        if (job?.isActive == true && !sessionBegun) stop()
    }

    /** Cancels session/backoff, goes Idle, clears failures; keeps the 429 deadline. */
    fun stop() {
        job?.cancel()
        job = null
        policy.reset()
        lastFrameAtMs = null
        _state.value = FeedState.Idle
    }

    private fun waitingState(): FeedState {
        val last = lastFrameAtMs ?: return FeedState.Connecting
        return FeedState.Reconnecting(last, policy.failuresInRow >= 3)
    }

    private companion object {
        const val RATE_LIMIT_MS = 60_000L
        const val HEALTHY_MS = 60_000L
    }
}

package com.nemuigames.nemuitv.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

private class FakeRunner(private val now: () -> Long) : SessionRunner {
    val startTimes = mutableListOf<Long>()
    var cancelled = false
    private var frame: (() -> Unit)? = null
    private var current: CompletableDeferred<SessionEnd>? = null

    override suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd {
        startTimes += now()
        frame = onFirstFrame
        val d = CompletableDeferred<SessionEnd>()
        current = d
        try {
            return d.await()
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
    }

    fun showFrame() = frame!!.invoke()
    fun end(e: SessionEnd) { current!!.complete(e) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraFeedTest {
    private class Rig(val test: TestScope, random: () -> Double = { 0.5 }) {
        val now = { test.testScheduler.currentTime }
        val runner = FakeRunner(now)
        val feed = CameraFeed("camera.x", runner, test.backgroundScope, now, ReconnectPolicy(random), now)
        fun settle() = test.testScheduler.runCurrent()
        fun advance(ms: Long) { test.testScheduler.advanceTimeBy(ms); test.testScheduler.runCurrent() }
    }

    private val failed = SessionEnd.Failed("x", "boom")

    @Test fun start_staggerThenConnecting() = runTest {
        val r = Rig(this)
        r.feed.start(3_000); r.settle()
        assertEquals(FeedState.Connecting, r.feed.state.value)
        assertTrue(r.runner.startTimes.isEmpty())
        r.advance(3_000)
        assertEquals(listOf(3_000L), r.runner.startTimes)
    }

    @Test fun `stopIfNotStarted stopsDuringDelay notAfterSessionBegan`() = runTest {
        val r = Rig(this)
        r.feed.start(5_000); r.settle()
        r.feed.stopIfNotStarted(); r.settle()
        assertEquals(FeedState.Idle, r.feed.state.value)
        r.advance(10_000)
        assertTrue(r.runner.startTimes.isEmpty())
        r.feed.start(0); r.advance(1)
        assertEquals(1, r.runner.startTimes.size)
        r.feed.stopIfNotStarted(); r.settle()
        assertEquals(FeedState.Connecting, r.feed.state.value)
    }

    @Test fun firstFrame_live() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.showFrame(); r.settle()
        assertEquals(FeedState.Live, r.feed.state.value)
    }

    @Test fun failed_backsOff10s() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(failed); r.settle()
        r.advance(9_999)
        assertEquals(1, r.runner.startTimes.size)
        r.advance(1)
        assertEquals(listOf(0L, 10_000L), r.runner.startTimes)
    }

    @Test fun errorBeforeAnswer_countsAsFailure() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(SessionEnd.Failed("webrtc_offer_failed", "no answer")); r.settle()
        r.advance(10_000)
        r.runner.end(failed); r.settle()
        // second failure -> 20 s
        r.advance(19_999)
        assertEquals(2, r.runner.startTimes.size)
        r.advance(1)
        assertEquals(3, r.runner.startTimes.size)
    }

    @Test fun threeFailures_showLastSeen() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.showFrame(); r.settle()
        r.advance(5_000)
        r.runner.end(SessionEnd.Dropped(5_000)); r.settle()           // failure 1
        assertEquals(FeedState.Reconnecting(5_000, false), r.feed.state.value)
        r.advance(10_000); r.runner.end(failed); r.settle()           // failure 2
        assertEquals(FeedState.Reconnecting(5_000, false), r.feed.state.value)
        r.advance(20_000); r.runner.end(failed); r.settle()           // failure 3
        assertEquals(FeedState.Reconnecting(5_000, true), r.feed.state.value)
    }

    @Test fun lastFrameAt_usesWallClock_notMonotonic() = runTest {
        val feed = CameraFeed("camera.x", FakeRunner { testScheduler.currentTime }.also { wallRunner = it }, backgroundScope,
            { testScheduler.currentTime }, ReconnectPolicy { 0.5 }, { 1_700_000_000_000L + testScheduler.currentTime })
        feed.start(0); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        wallRunner.showFrame(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        wallRunner.end(SessionEnd.Dropped(2_000)); testScheduler.runCurrent()
        val st = feed.state.value as FeedState.Reconnecting
        assertEquals(1_700_000_000_000L + 3_000, st.lastFrameAtMs)
        assertTrue(st.lastFrameAtMs!! >= 1_700_000_000_000L)
    }
    private lateinit var wallRunner: FakeRunner

    @Test fun `dropAfterLive reconnectingKeepsLastFrameTime`() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.advance(1_000)
        r.runner.showFrame(); r.settle()
        r.advance(2_000)
        r.runner.end(SessionEnd.Dropped(2_000)); r.settle()
        assertEquals(FeedState.Reconnecting(3_000, false), r.feed.state.value)
        r.advance(10_000)
        assertEquals(2, r.runner.startTimes.size)
        assertEquals(FeedState.Reconnecting(3_000, false), r.feed.state.value)
    }

    @Test fun healthyDrop_resetsBackoff() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.showFrame(); r.settle()
        r.advance(61_000)
        r.runner.end(SessionEnd.Dropped(61_000)); r.settle()
        assertEquals(listOf(0L, 61_000L), r.runner.startTimes)
    }

    @Test fun rateLimited_waits60s() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(SessionEnd.Failed("429", "RESOURCE_EXHAUSTED")); r.settle()
        r.advance(59_999)
        assertEquals(1, r.runner.startTimes.size)
        r.advance(1)
        assertEquals(listOf(0L, 60_000L), r.runner.startTimes)
    }

    @Test fun stopDuringBackoff_cancelsRetry() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(failed); r.settle()
        r.feed.stop()
        r.advance(300_000)
        assertEquals(1, r.runner.startTimes.size)
        assertEquals(FeedState.Idle, r.feed.state.value)
    }

    @Test fun restartAfter429_stillWaits60s() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(SessionEnd.Failed("429", "RESOURCE_EXHAUSTED")); r.settle()
        r.advance(10_000)
        r.feed.stop(); r.settle()
        r.feed.start(0); r.settle()
        r.advance(49_999)
        assertEquals(1, r.runner.startTimes.size)
        r.advance(1)
        assertEquals(listOf(0L, 60_000L), r.runner.startTimes)
    }

    @Test fun `subscriptionClosed reconnectsWithBackoff`() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.end(SessionEnd.Failed(null, "subscription closed")); r.settle()
        r.advance(10_000)
        assertEquals(listOf(0L, 10_000L), r.runner.startTimes)
    }

    @Test fun stop_cancelsRunningSession() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.feed.stop(); r.settle()
        assertTrue(r.runner.cancelled)
        assertEquals(FeedState.Idle, r.feed.state.value)
        r.advance(300_000)
        assertEquals(1, r.runner.startTimes.size)
    }

    @Test fun `runnerThrows countsAsFailure andRetriesAfterBackoff`() = runTest {
        var calls = 0
        val times = mutableListOf<Long>()
        val now = { testScheduler.currentTime }
        val throwing = object : SessionRunner {
            override suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd {
                times += now()
                if (calls++ == 0) throw IllegalStateException("peer broke")
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val feed = CameraFeed("camera.x", throwing, backgroundScope, now, ReconnectPolicy { 0.5 })
        feed.start(0); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(9_999); testScheduler.runCurrent()
        assertEquals(listOf(0L), times)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()
        assertEquals(listOf(0L, 10_000L), times)
    }

    @Test fun firstFrameAfterStop_ignored() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.feed.stop(); r.settle()
        r.runner.showFrame(); r.settle()
        assertEquals(FeedState.Idle, r.feed.state.value)
    }

    @Test fun `stopThenStart showsConnectingNotReconnecting`() = runTest {
        val r = Rig(this)
        r.feed.start(0); r.settle()
        r.runner.showFrame(); r.settle()
        r.feed.stop(); r.settle()
        r.feed.start(0); r.settle()
        assertEquals(FeedState.Connecting, r.feed.state.value)
    }
}

package com.nemuigames.nemuitv.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** Records when each camera's session starts; sessions stay open until cancelled. */
private class BankFakeRunner(private val now: () -> Long) : SessionRunner {
    val starts = mutableListOf<Pair<String, Long>>()
    val cancelled = mutableListOf<String>()

    override suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd {
        starts += entityId to now()
        try {
            return CompletableDeferred<SessionEnd>().await()
        } catch (e: CancellationException) {
            cancelled += entityId
            throw e
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraBankTest {
    private val cams = listOf("camera.doorbell", "camera.front", "camera.back")

    private class Rig(val test: TestScope) {
        val now = { test.testScheduler.currentTime }
        val runner = BankFakeRunner(now)
        val bank = CameraBank(test.backgroundScope, now) { runner }
        fun settle() = test.testScheduler.runCurrent()
        fun advance(ms: Long) { test.testScheduler.advanceTimeBy(ms); test.testScheduler.runCurrent() }
    }

    @Test fun visible_startsStaggeredAfterDebounce() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(499)
        assertTrue(r.runner.starts.isEmpty())
        r.advance(10_000)
        assertEquals(
            listOf("camera.doorbell" to 500L, "camera.front" to 1_500L, "camera.back" to 2_500L),
            r.runner.starts,
        )
    }

    @Test fun quickPass_startsNothing() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(300)
        r.bank.setVisible(cams, false); r.settle()
        r.advance(30_000)
        assertTrue(r.runner.starts.isEmpty())
        cams.forEach { assertEquals(FeedState.Idle, r.bank.feed(it).state.value) }
    }

    @Test fun hidden_keepsFeedsForGrace_thenStops() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        assertEquals(3, r.runner.starts.size)
        r.bank.setVisible(cams, false); r.settle()
        r.advance(CAMERA_GRACE_MS - 1)
        assertTrue(r.runner.cancelled.isEmpty())
        cams.forEach { assertTrue(r.bank.feed(it).state.value != FeedState.Idle) }
        r.advance(1)
        assertEquals(cams.toSet(), r.runner.cancelled.toSet())
        cams.forEach { assertEquals(FeedState.Idle, r.bank.feed(it).state.value) }
        r.advance(120_000)
        assertEquals(3, r.runner.starts.size) // nothing restarts on its own
    }

    @Test fun reopenWithinGrace_noNewSession() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.setVisible(cams, false); r.settle()
        r.advance(30_000)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(120_000)
        assertEquals(3, r.runner.starts.size)
        assertTrue(r.runner.cancelled.isEmpty())
        cams.forEach { assertTrue(r.bank.feed(it).state.value != FeedState.Idle) }
    }

    @Test fun `stopNow stopsImmediately andCancelsGrace`() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.setVisible(cams, false); r.settle()
        r.bank.stopNow(); r.settle()
        assertEquals(cams.toSet(), r.runner.cancelled.toSet())
        cams.forEach { assertEquals(FeedState.Idle, r.bank.feed(it).state.value) }
        // grace was cancelled: a reopen after stopNow starts fresh sessions and the old timer must not kill them
        r.bank.setVisible(cams, true); r.settle()
        r.advance(CAMERA_GRACE_MS)
        assertEquals(6, r.runner.starts.size)
        assertEquals(3, r.runner.cancelled.size)
    }

    @Test fun stopNow_cancelsPendingStart() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(100)
        r.bank.stopNow(); r.settle()
        r.advance(30_000)
        assertTrue(r.runner.starts.isEmpty())
    }

    @Test fun `hiddenDuringStagger laterCamerasNeverStart doorbellGetsGrace`() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(1_000) // doorbell running, the other two still in their stagger delay
        r.bank.setVisible(cams, false); r.settle()
        assertEquals(FeedState.Idle, r.bank.feed("camera.front").state.value)
        assertEquals(FeedState.Idle, r.bank.feed("camera.back").state.value)
        r.advance(CAMERA_GRACE_MS - 1)
        assertEquals(listOf("camera.doorbell" to 500L), r.runner.starts)
        assertTrue(r.runner.cancelled.isEmpty())
        r.advance(1)
        assertEquals(listOf("camera.doorbell"), r.runner.cancelled)
    }

    @Test fun aspect_defaults16by9_thenReported() = runTest {
        val r = Rig(this)
        assertEquals(16f / 9f, r.bank.videoAspect("camera.doorbell").value, 0.0001f)
        r.bank.onVideoSize("camera.doorbell", 960, 1280)
        assertEquals(0.75f, r.bank.videoAspect("camera.doorbell").value, 0.0001f)
        r.bank.onVideoSize("camera.doorbell", 0, 0) // garbage sizes are ignored
        assertEquals(0.75f, r.bank.videoAspect("camera.doorbell").value, 0.0001f)
        assertEquals(16f / 9f, r.bank.videoAspect("camera.front").value, 0.0001f)
    }

    @Test fun sinks_fanOut_addAndRemove() = runTest {
        val r = Rig(this)
        val got = mutableListOf<String>()
        val tile = org.webrtc.VideoSink { got += "tile" }
        val popup = org.webrtc.VideoSink { got += "popup" }
        assertEquals(0, r.bank.sinksFor("camera.doorbell").size)
        r.bank.addSink("camera.doorbell", tile)
        r.bank.addSink("camera.doorbell", popup)
        r.bank.addSink("camera.doorbell", popup) // idempotent
        assertEquals(2, r.bank.sinksFor("camera.doorbell").size)
        r.bank.sinksFor("camera.doorbell").forEach { it.onFrame(null) } // what the runner does per frame
        assertEquals(setOf("tile", "popup"), got.toSet())
        assertEquals(2, got.size)

        got.clear()
        r.bank.removeSink("camera.doorbell", tile) // the hub tile goes away: the pop-up keeps its frames
        r.bank.sinksFor("camera.doorbell").forEach { it.onFrame(null) }
        assertEquals(listOf("popup"), got)
        assertEquals(0, r.bank.sinksFor("camera.front").size) // per camera

        r.bank.removeSink("camera.doorbell", popup)
        r.bank.removeSink("camera.doorbell", popup) // safe when absent
        assertEquals(0, r.bank.sinksFor("camera.doorbell").size)
    }

    // --- M4 alert feed ---

    @Test fun alertFeed_startsImmediately_noDebounce() = runTest {
        val r = Rig(this)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        assertEquals(listOf("camera.doorbell" to 0L), r.runner.starts)
    }

    @Test fun alertFeed_cutsHubStaggerDelay() = runTest {
        val r = Rig(this)
        r.bank.setVisible(listOf("camera.front", "camera.doorbell"), true); r.settle()
        r.advance(600) // front running, doorbell waiting out its 1 s stagger
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        assertEquals(listOf("camera.front" to 500L, "camera.doorbell" to 600L), r.runner.starts)
        r.advance(10_000)
        assertEquals(2, r.runner.starts.size) // the hub's later start reuses it
    }

    @Test fun alertFeed_reusesHubFeed_releaseKeepsHub() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle() // idempotent
        assertEquals(3, r.runner.starts.size)
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle()
        r.advance(60_000)
        assertTrue(r.runner.cancelled.isEmpty())
        assertTrue(r.bank.feed("camera.doorbell").state.value != FeedState.Idle)
        assertEquals(3, r.runner.starts.size)
    }

    @Test fun `alertFeed release stopsWhenHubNotHolding`() = runTest {
        val r = Rig(this)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.advance(5_000)
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle()
        assertEquals(listOf("camera.doorbell"), r.runner.cancelled)
        assertEquals(FeedState.Idle, r.bank.feed("camera.doorbell").state.value)
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle() // safe when not held
        r.bank.releaseAlertFeed("camera.front"); r.settle()
        r.advance(120_000)
        assertEquals(1, r.runner.starts.size)
    }

    @Test fun `alertFeed releaseDuringHubGrace graceStopsIt`() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.setVisible(cams, false); r.settle()
        r.advance(10_000)
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle()
        assertTrue(r.runner.cancelled.isEmpty()) // hub still holds it (grace)
        r.advance(CAMERA_GRACE_MS)
        assertEquals(cams.toSet(), r.runner.cancelled.toSet())
    }

    @Test fun stopNow_keepsAlertHeldFeed() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.stopNow(); r.settle()
        assertEquals(setOf("camera.front", "camera.back"), r.runner.cancelled.toSet())
        assertTrue(r.bank.feed("camera.doorbell").state.value != FeedState.Idle)
        // the hub no longer holds it, so releasing the alert stops it at once
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle()
        assertEquals(cams.toSet(), r.runner.cancelled.toSet())
        assertEquals(3, r.runner.starts.size)
    }

    @Test fun hubHide_keepsAlertHeldFeed() = runTest {
        val r = Rig(this)
        r.bank.setVisible(cams, true); r.settle()
        r.advance(7_000)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.setVisible(cams, false); r.settle()
        r.advance(CAMERA_GRACE_MS)
        assertEquals(setOf("camera.front", "camera.back"), r.runner.cancelled.toSet())
        assertTrue(r.bank.feed("camera.doorbell").state.value != FeedState.Idle)
        r.bank.releaseAlertFeed("camera.doorbell"); r.settle()
        assertEquals(cams.toSet(), r.runner.cancelled.toSet())
    }

    @Test fun hubShowsOtherCameras_keepsAlertHeldFeed() = runTest {
        val r = Rig(this)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.setVisible(listOf("camera.front"), true); r.settle()
        r.advance(5_000)
        assertTrue(r.runner.cancelled.isEmpty())
        assertTrue(r.bank.feed("camera.doorbell").state.value != FeedState.Idle)
    }

    @Test fun release_stopsAlertHeldFeedToo() = runTest {
        val r = Rig(this)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        r.bank.release(); r.settle()
        assertEquals(listOf("camera.doorbell"), r.runner.cancelled)
        r.bank.acquireAlertFeed("camera.doorbell"); r.settle()
        assertEquals(1, r.runner.starts.size)
    }
}

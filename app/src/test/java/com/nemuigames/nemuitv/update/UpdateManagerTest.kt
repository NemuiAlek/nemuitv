package com.nemuigames.nemuitv.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeInstaller : InstallerPort {
        @Volatile var allowed = true
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun canInstall() = allowed
        override fun openPermissionSettings() { log += "settings" }
        override fun commit(apk: File) { log += "commit" }
    }

    private val apk = ByteArray(10_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var releaseCodes = ArrayDeque<Int>()
    @Volatile private var slowApk = false

    private val server: MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return when {
                    path.endsWith("/releases/latest") -> {
                        val code = synchronized(this) { releaseCodes.removeFirstOrNull() ?: 200 }
                        if (code != 200) MockResponse().setResponseCode(code) else MockResponse().setBody(releaseJson())
                    }
                    path == "/apk" -> MockResponse().setBody(Buffer().write(apk)).also {
                        if (slowApk) it.throttleBody(2_000, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    path == "/sha" -> MockResponse().setBody("$apkSha  NemuiTV-1.2.1.apk\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    private fun releaseJson(): String = """{"tag_name":"v1.2.1","body":"notes","assets":[
        {"name":"NemuiTV-1.2.1.apk","size":${apk.size},"browser_download_url":"${server.url("/apk")}"},
        {"name":"NemuiTV-1.2.1.apk.sha256","size":65,"browser_download_url":"${server.url("/sha")}"}]}"""

    private fun count(path: String) = hits[path]?.get() ?: 0
    private val checks get() = count("/repos/NemuiAlek/nemuitv/releases/latest")

    private val ts = TestScope()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + StandardTestDispatcher(ts.testScheduler))
    private val installer = FakeInstaller()
    private var now = 1_000L
    private var closes = 0
    private val net = UpdateNet(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    private val manager by lazy { UpdateManager(
        scope, net, installer, "1.2.0", tmp.newFolder("cache"), { now },
        closeCc = { closes++; installer.log += "close" },
    ) }

    /** Cancels the manager's scope at the end: its 6 h loop would otherwise keep runTest advancing virtual time forever. */
    private fun runT(body: suspend TestScope.() -> Unit) = ts.runTest {
        try { body() } finally { job.cancel() }
    }

    @After fun stop() { job.cancel(); server.shutdown() }

    /** Real IO threads do the networking, so let them finish between runCurrent() calls. */
    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            ts.testScheduler.runCurrent()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun primeAvailable() {
        manager.onHomeResumed()
        awaitUntil("available") { manager.available.value != null }
    }

    @Test fun firstCheckAfter30sShowsAvailable() = runT {
        manager.start()
        advanceTimeBy(UpdateSchedule.FIRST_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, checks)
        assertNull(manager.available.value)
        advanceTimeBy(1)
        awaitUntil("available") { manager.available.value != null }
        assertEquals("1.2.1", manager.available.value!!.versionText)
    }

    @Test fun failedCheckRetriesOnNextResume() = runT {
        releaseCodes = ArrayDeque(listOf(500))
        // Keep resuming until the retry lands: the first check may still be finishing when we resume again.
        awaitUntil("available") {
            manager.onHomeResumed()
            manager.available.value != null
        }
        assertTrue(checks >= 2)
    }

    @Test fun successfulCheckNotRepeatedWithin6h() = runT {
        primeAvailable()
        now += 3_600_000
        manager.onHomeResumed()
        runCurrent(); Thread.sleep(100); runCurrent()
        assertEquals(1, checks)
        now += UpdateSchedule.INTERVAL_MS
        manager.onHomeResumed()
        awaitUntil("second check") { checks == 2 }
    }

    @Test fun noPermissionGoesToNeedsPermission_thenAllowAndResumeReopensAsIdle() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installer.allowed = false
        manager.onInstallPressed()
        assertEquals(UpdateState.NeedsPermission, manager.state.value)
        manager.onAllowPressed()
        assertEquals(listOf("close", "settings"), installer.log)
        installer.allowed = true
        manager.onHomeResumed()
        runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(1, reopens)
        manager.onHomeResumed()
        runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun installHappyPath() = runT {
        primeAvailable()
        manager.onInstallPressed()
        assertEquals(UpdateState.Downloading(0), manager.state.value)
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        assertEquals(listOf("close", "commit"), installer.log)
        manager.onInstallStatus(PackageInstaller.STATUS_SUCCESS, null)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test fun doubleInstallPressStartsOneDownload() = runT {
        primeAvailable()
        slowApk = true
        manager.onInstallPressed()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        manager.onInstallPressed() // ignored while Installing too
        runCurrent()
        assertEquals(1, count("/apk"))
        assertEquals(1, installer.log.count { it == "commit" })
    }

    @Test fun downloadSurvivesPanelClose() = runT {
        primeAvailable()
        slowApk = true
        manager.onInstallPressed()
        awaitUntil("downloading") { manager.state.value is UpdateState.Downloading }
        // A check finishing mid-download must not reset the state.
        now += UpdateSchedule.INTERVAL_MS
        manager.onHomeResumed()
        awaitUntil("second check") { checks == 2 }
        runCurrent(); Thread.sleep(100); runCurrent()
        assertTrue(manager.state.value is UpdateState.Downloading)
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        assertEquals(1, count("/apk"))
    }

    @Test fun missedLiveReopenIsDeliveredOnNextResumeExactlyOnce() = runT {
        primeAvailable()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        // Cancel arrives while no host collects: nothing is lost.
        manager.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        assertEquals(0, reopens)
        manager.onHomeResumed(); runCurrent()
        assertEquals(1, reopens)
        manager.onHomeResumed(); runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun liveReopenIsNotRepeatedOnResume() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        manager.onHomeResumed(); runCurrent() // prompt still covering Home: no early reopen
        assertEquals(0, reopens)
        manager.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        runCurrent()
        assertEquals(1, reopens)
        manager.onHomeResumed(); runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun cancelledReopensWithCancelled() = runT {
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, "Aborted")
        runCurrent()
        assertEquals(UpdateState.Cancelled, manager.state.value)
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun failureReopensWithReason() = runT {
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onInstallStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "Conflicts with an existing package")
        runCurrent()
        assertEquals(UpdateState.InstallFailed("Conflicts with an existing package"), manager.state.value)
        assertEquals(1, reopens)
        manager.onInstallStatus(PackageInstaller.STATUS_FAILURE, null)
        assertEquals(UpdateState.InstallFailed("unknown error"), manager.state.value)
        coroutineContext.cancelChildren()
    }

    @Test fun resumeWithoutLeavingDoesNotReopen() = runT {
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onHomeResumed()
        runCurrent()
        assertEquals(0, reopens)
        coroutineContext.cancelChildren()
    }
}

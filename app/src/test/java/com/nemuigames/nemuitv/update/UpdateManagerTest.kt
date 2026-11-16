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
        @Volatile var lastId = 0
        val open = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        override fun createSession(apk: File) = (++lastId).also { open += it }
        override fun commit(sessionId: Int) { log += "commit" }
        override fun abandon(sessionId: Int) { log += "abandon $sessionId"; open -= sessionId }
        override fun isSessionOpen(sessionId: Int) = sessionId in open
        override fun abandonOwnSessions() { log += "abandon own" }
    }

    private val apk = ByteArray(10_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var releaseCodes = ArrayDeque<Int>()
    @Volatile private var slowApk = false
    @Volatile private var slowRelease = false
    @Volatile private var tag = "1.2.1"

    private val server: MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return when {
                    path.endsWith("/releases/latest") -> {
                        val code = synchronized(this) { releaseCodes.removeFirstOrNull() ?: 200 }
                        if (code != 200) MockResponse().setResponseCode(code) else MockResponse().setBody(releaseJson()).also {
                            if (slowRelease) it.setHeadersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
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

    private fun releaseJson(): String = """{"tag_name":"v$tag","body":"notes","assets":[
        {"name":"NemuiTV-$tag.apk","size":${apk.size},"browser_download_url":"${server.url("/apk")}"},
        {"name":"NemuiTV-$tag.apk.sha256","size":65,"browser_download_url":"${server.url("/sha")}"}]}"""

    private fun count(path: String) = hits[path]?.get() ?: 0
    private val checks get() = count("/repos/NemuiAlek/nemuitv/releases/latest")

    private val ts = TestScope()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + StandardTestDispatcher(ts.testScheduler))
    private val installer = FakeInstaller()
    private var now = 1_000L
    private var closes = 0
    private val net = UpdateNet(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    private val cacheDir by lazy { tmp.newFolder("cache") }
    private val manager by lazy { UpdateManager(
        scope, net, installer, "1.2.0", cacheDir, { now },
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

    @Test fun `start clears leftovers from an earlier process`() = runT {
        val leftover = File(cacheDir, "NemuiTV-1.2.1.apk").apply { writeBytes(apk) }
        manager.start()
        awaitUntil("leftovers cleared") { !leftover.exists() && "abandon own" in installer.log }
        assertTrue(cacheDir.isDirectory)
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

    @Test fun `debug check now skips the 6 h gate and uses the new source`() = runT {
        primeAvailable()
        assertEquals(1, checks)
        val other = UpdateNet(OkHttpClient(), server.url("/other").toString().trimEnd('/'))
        manager.debugCheckNow(other)
        awaitUntil("check on the new source") { count("/other/repos/NemuiAlek/nemuitv/releases/latest") == 1 }
        assertEquals(1, checks)
    }

    @Test fun `check now when up to date says so for a moment then goes back`() = runT {
        tag = "1.2.0"
        manager.checkNow()
        awaitUntil("up to date") { manager.check.value == UpdateCheck.UpToDate }
        assertNull(manager.available.value)
        advanceTimeBy(UpdateManager.CHECK_RESULT_MS + 1)
        assertEquals(UpdateCheck.Idle, manager.check.value)
    }

    @Test fun `check now skips the 6 h gate and shows the row`() = runT {
        tag = "1.2.0"
        manager.checkNow()
        awaitUntil("up to date") { manager.check.value == UpdateCheck.UpToDate }
        tag = "1.2.1"
        manager.checkNow()
        awaitUntil("row") { manager.available.value != null }
        assertEquals(UpdateCheck.Idle, manager.check.value)
        assertEquals(2, checks)
        // It counts as the latest good check: a resume right after doesn't ask GitHub again.
        manager.onHomeResumed()
        runCurrent(); Thread.sleep(100); runCurrent()
        assertEquals(2, checks)
    }

    @Test fun `check now failure says couldn't check and shows no row`() = runT {
        releaseCodes = ArrayDeque(listOf(500))
        manager.checkNow()
        awaitUntil("failed") { manager.check.value == UpdateCheck.Failed }
        assertNull(manager.available.value)
        advanceTimeBy(UpdateManager.CHECK_RESULT_MS + 1)
        assertEquals(UpdateCheck.Idle, manager.check.value)
    }

    @Test fun `pressing check twice while checking asks GitHub once`() = runT {
        slowRelease = true
        manager.checkNow()
        runCurrent()
        assertEquals(UpdateCheck.Checking, manager.check.value)
        manager.checkNow()
        awaitUntil("row") { manager.available.value != null }
        assertEquals(1, checks)
    }

    @Test fun `no permission goes to needs permission then allow and resume reopens as idle`() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installer.allowed = false
        manager.onInstallPressed()
        assertEquals(UpdateState.NeedsPermission, manager.state.value)
        manager.onAllowPressed()
        assertEquals(listOf("close", "settings"), installer.log)
        // Home passes through the front on its way behind Settings: not a return yet.
        manager.onHomeResumed(); runCurrent()
        assertEquals(0, reopens)
        installer.allowed = true
        now += UpdateManager.SETTINGS_SETTLE_MS
        manager.onHomeResumed()
        runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(1, reopens)
        manager.onHomeResumed()
        runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun `resume long after leaving for settings does not reopen`() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installer.allowed = false
        manager.onInstallPressed()
        manager.onAllowPressed()
        // Back from Settings went to another app; Home is pressed hours later.
        now += UpdateManager.REOPEN_WINDOW_MS + 1
        manager.onHomeResumed(); runCurrent()
        assertEquals(0, reopens)
        // The flag is gone for good, not just skipped once.
        manager.onHomeResumed(); runCurrent()
        assertEquals(0, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun `resume within the window after leaving for settings reopens`() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installer.allowed = false
        manager.onInstallPressed()
        manager.onAllowPressed()
        manager.onHomeResumed(); runCurrent() // home passing through behind Settings
        now += UpdateManager.REOPEN_WINDOW_MS
        manager.onHomeResumed(); runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun installHappyPath() = runT {
        primeAvailable()
        manager.onInstallPressed()
        assertEquals(UpdateState.Downloading(0), manager.state.value)
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        assertEquals(listOf("close", "commit"), installer.log)
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_SUCCESS, null)
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

    @Test fun `missed live reopen is delivered on next resume exactly once`() = runT {
        primeAvailable()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        // Cancel arrives while no host collects: nothing is lost.
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE_ABORTED, null)
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
        manager.onHomeResumed(); runCurrent() // left the prompt with Home: no reopen
        assertEquals(0, reopens)
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        runCurrent()
        assertEquals(1, reopens)
        manager.onHomeResumed(); runCurrent()
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    private var prompts = 0

    /** Install up to Android's confirm prompt (PENDING_USER_ACTION shows it); returns the session id. */
    private fun installToPrompt(): Int {
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        val id = installer.lastId
        manager.onConfirmNeeded(id) { prompts++ }
        return id
    }

    /** Home resumes with the prompt gone and no result: after the grace the page is plain Install again. */
    private fun TestScope.homeOut() {
        manager.onHomeResumed(); runCurrent()
        assertEquals(UpdateState.Installing, manager.state.value) // still waiting for a Cancel/Back result
        advanceTimeBy(UpdateManager.PROMPT_RESULT_GRACE_MS + 1); runCurrent()
    }

    @Test fun `home out gives plain install without reopen or abandon`() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installToPrompt()
        homeOut()
        assertEquals(UpdateState.Idle, manager.state.value) // no "Cancelled": the user just left
        assertEquals(0, reopens)
        assertTrue(installer.log.none { it.startsWith("abandon") })
        coroutineContext.cancelChildren()
    }

    @Test fun `confirmed install still verifying is never abandoned`() = runT {
        primeAvailable()
        val id = installToPrompt()
        // User pressed Install on the prompt: it closes, Home resumes, the install runs longer than the grace.
        homeOut()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(installer.log.none { it.startsWith("abandon") })
        assertTrue(id in installer.open)
        manager.onInstallStatus(id, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test fun `install after home out shows the same prompt again`() = runT {
        primeAvailable()
        val id = installToPrompt()
        homeOut()
        manager.onInstallPressed()
        assertEquals(UpdateState.Installing, manager.state.value)
        assertEquals(2, prompts)
        runCurrent(); Thread.sleep(100); runCurrent()
        assertEquals(1, count("/apk"))
        assertEquals(1, installer.log.count { it == "commit" })
        assertEquals(id, installer.lastId)
        manager.onInstallStatus(id, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test fun `install after home out with session gone starts fresh`() = runT {
        primeAvailable()
        val first = installToPrompt()
        homeOut()
        installer.open -= first // e.g. Android dropped it
        manager.onInstallPressed()
        assertEquals(UpdateState.Downloading(0), manager.state.value)
        // A late result from the old session can't touch the new attempt.
        manager.onInstallStatus(first, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertTrue(manager.state.value is UpdateState.Downloading)
        awaitUntil("installing again") { manager.state.value == UpdateState.Installing }
        assertEquals(2, installer.log.count { it == "commit" })
        assertEquals(first + 1, installer.lastId)
        manager.onInstallStatus(first, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateState.Installing, manager.state.value)
    }

    @Test fun lateCancelSoonAfterHomeOutStillReopens() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        val id = installToPrompt()
        homeOut()
        now += UpdateManager.LATE_CANCEL_REOPEN_MS
        manager.onInstallStatus(id, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        runCurrent()
        assertEquals(UpdateState.Cancelled, manager.state.value)
        assertEquals(1, reopens)
        assertEquals(listOf("abandon $id"), installer.log.filter { it.startsWith("abandon") })
        coroutineContext.cancelChildren()
    }

    @Test fun lateCancelLongAfterHomeOutIsQuiet() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        val id = installToPrompt()
        homeOut()
        now += UpdateManager.LATE_CANCEL_REOPEN_MS + 1
        manager.onInstallStatus(id, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        runCurrent()
        assertEquals(UpdateState.Cancelled, manager.state.value)
        assertEquals(0, reopens)
        assertEquals(listOf("abandon $id"), installer.log.filter { it.startsWith("abandon") })
        // Nothing left over for a later resume either.
        manager.onHomeResumed(); runCurrent()
        assertEquals(0, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun `late cancel long after home out with no host is not delivered on resume`() = runT {
        primeAvailable()
        val id = installToPrompt()
        homeOut()
        now += UpdateManager.LATE_CANCEL_REOPEN_MS + 1
        manager.onInstallStatus(id, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onHomeResumed(); runCurrent()
        assertEquals(0, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun `missing confirm prompt fails instead of hanging`() = runT {
        primeAvailable()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        manager.onConfirmNeeded(installer.lastId, null)
        assertEquals(UpdateState.InstallFailed("no install prompt"), manager.state.value)
        assertTrue(installer.log.contains("abandon ${installer.lastId}"))
    }

    @Test fun `back result after home resume still reopens and abandons once`() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
        // Back on the prompt: Home may resume a moment before the ABORTED result arrives.
        manager.onHomeResumed(); runCurrent()
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        runCurrent()
        advanceTimeBy(UpdateManager.PROMPT_RESULT_GRACE_MS + 1); runCurrent()
        assertEquals(UpdateState.Cancelled, manager.state.value)
        assertEquals(1, reopens)
        assertEquals(listOf("abandon ${installer.lastId}"), installer.log.filter { it.startsWith("abandon") })
        coroutineContext.cancelChildren()
    }

    private fun installUntilPrompt() {
        manager.onInstallPressed()
        awaitUntil("installing") { manager.state.value == UpdateState.Installing }
    }

    @Test fun cancelledReopensWithCancelled() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installUntilPrompt()
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE_ABORTED, "Aborted")
        runCurrent()
        assertEquals(UpdateState.Cancelled, manager.state.value)
        assertEquals(1, reopens)
        coroutineContext.cancelChildren()
    }

    @Test fun failureReopensWithReason() = runT {
        primeAvailable()
        var reopens = 0
        manager.reopenRequests.onEach { reopens++ }.launchIn(this)
        runCurrent()
        installUntilPrompt()
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE_CONFLICT, "Conflicts with an existing package")
        runCurrent()
        assertEquals(UpdateState.InstallFailed("Conflicts with an existing package"), manager.state.value)
        assertEquals(1, reopens)
        installUntilPrompt()
        manager.onInstallStatus(installer.lastId, PackageInstaller.STATUS_FAILURE, null)
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

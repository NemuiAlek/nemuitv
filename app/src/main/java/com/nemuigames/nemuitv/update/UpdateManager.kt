package com.nemuigames.nemuitv.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.nemuigames.nemuitv.HomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the Update page shows for the button and its message. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object NeedsPermission : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data object Cancelled : UpdateState
    data object Mismatch : UpdateState
    data object DownloadFailed : UpdateState
    data class InstallFailed(val reason: String) : UpdateState
}

/** The Android side of installing, behind an interface so the manager can be tested on the JVM. */
interface InstallerPort {
    fun canInstall(): Boolean
    fun openPermissionSettings()
    /** Creates an install session holding [apk] and returns its id. */
    fun createSession(apk: File): Int
    /** Commits the session; the result arrives later through [InstallStatusReceiver]. */
    fun commit(sessionId: Int)
    /** Drops a session that will never be confirmed (its prompt then can't install or report into a later attempt). */
    fun abandon(sessionId: Int)
    /** Whether Android still has the session (false once it installed, failed or was abandoned). */
    fun isSessionOpen(sessionId: Int): Boolean
    /** Drops every session this app still has open: a fresh process never resumes one, so they are leftovers. */
    fun abandonOwnSessions()
}

/**
 * Finds newer releases and runs the install. Lives in NemuiCore so a download keeps going and the
 * state survives while the Control Center is closed (it must be hidden before Settings or the
 * install prompt, since the overlay draws above system screens).
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private var net: UpdateNet,
    private val installer: InstallerPort,
    private val installedVersion: String,
    private val cacheDir: File,
    private val clock: () -> Long,
    private val closeCc: () -> Unit,
) {
    private val _available = MutableStateFlow<ReleaseInfo?>(null)
    /** A release newer than the installed one; the settings row shows while this is non-null. */
    val available: StateFlow<ReleaseInfo?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _reopen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Asks a Control Center host to open on the Update page. */
    val reopenRequests: SharedFlow<Unit> = _reopen.asSharedFlow()

    private var lastSuccessMs: Long? = null
    private var checking = false
    /**
     * When the user last left from the Update page (Settings, the install prompt, or a result no host saw), or null.
     * Resume reopens the page only while this is recent, so a stale flag can't pop the page up hours later.
     */
    private var leftAtMs: Long? = null
    /** When a prompt left with Home was reset to plain Install; a late Cancel soon after still reopens the page. */
    private var homeOutAtMs: Long? = null
    /** When "Allow installs first" opened Settings; see [SETTINGS_SETTLE_MS]. */
    private var settingsOpenedAtMs: Long? = null
    private var apkFile: File? = null
    /** The session whose result the page waits for; results from any other session are ignored. */
    private var session: Int? = null
    /** Shows [session]'s confirm prompt (from its PENDING_USER_ACTION); kept so a prompt left with Home can come back. */
    private var showConfirm: (() -> Unit)? = null

    /**
     * Clears what an earlier process left behind (an APK whose install restarted the app before it could delete it,
     * sessions nobody will confirm), then checks after a short delay and every six hours while the app runs.
     */
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            runCatching { installer.abandonOwnSessions() }
        }
        scope.launch {
            delay(UpdateSchedule.FIRST_DELAY_MS)
            while (true) {
                checkIfDue()
                delay(UpdateSchedule.INTERVAL_MS)
            }
        }
    }

    /** Called on every Home resume (also after the TV wakes): the reopen rule plus a due check. */
    fun onHomeResumed() {
        // Home resumes once the confirm prompt is gone. Cancel/Back send their result right after; Home on the prompt
        // sends none, and neither does a confirmed install still being verified (it ends with SUCCESS, which restarts the
        // app). So after a short wait the button comes back as plain Install, with no message and no reopen (the user
        // chose Home), and the session is kept: Install shows its prompt again, and a late result still counts.
        val waiting = session
        if (_state.value == UpdateState.Installing && waiting != null) {
            scope.launch {
                delay(PROMPT_RESULT_GRACE_MS)
                if (session != waiting || _state.value != UpdateState.Installing) return@launch
                leftAtMs = null
                homeOutAtMs = clock()
                _state.value = UpdateState.Idle
            }
        }
        val leftAt = leftAtMs
        // Opening Settings brings home to the front for a moment first (so Back lands there): that resume isn't a return.
        val settingsJustOpened = settingsOpenedAtMs?.let { clock() - it < SETTINGS_SETTLE_MS } == true
        if (leftAt != null && _state.value != UpdateState.Installing && !settingsJustOpened) {
            settingsOpenedAtMs = null
            leftAtMs = null
            if (_state.value == UpdateState.NeedsPermission && installer.canInstall()) {
                _state.value = UpdateState.Idle
            }
            // Left long ago (e.g. Back from Settings went to another app): just drop the flag, no surprise page.
            if (clock() - leftAt <= REOPEN_WINDOW_MS) _reopen.tryEmit(Unit)
        }
        scope.launch { checkIfDue() }
    }

    /**
     * Debug builds only (DebugAlertReceiver `update-base`): read releases from [net] from now on and check right
     * away, skipping the 6 h gate. Nothing in the main source set calls it, so release builds keep the GitHub URL.
     */
    fun debugCheckNow(net: UpdateNet) {
        this.net = net
        lastSuccessMs = null
        scope.launch { checkIfDue() }
    }

    private suspend fun checkIfDue() {
        if (checking || !UpdateSchedule.due(clock(), lastSuccessMs)) return
        checking = true
        try {
            // A null result is a failure (or no release at all): the next resume tries again.
            val latest = net.latest() ?: return
            lastSuccessMs = clock()
            // Only touches `available`: a check landing mid-download must not reset `state`.
            _available.value = newerRelease(installedVersion, latest)
        } finally {
            checking = false
        }
    }

    fun onInstallPressed() {
        val release = _available.value ?: return
        when (_state.value) {
            is UpdateState.Downloading, UpdateState.Installing -> return
            else -> Unit
        }
        val waiting = session
        if (waiting != null) {
            val confirm = showConfirm
            if (confirm != null && installer.isSessionOpen(waiting)) {
                // The prompt was left with Home: show that same prompt again, no new download or session.
                closeCc()
                leftAtMs = clock()
                homeOutAtMs = null
                _state.value = UpdateState.Installing
                launchConfirm(confirm)
                return
            }
            // Android no longer has it (installed, failed or dropped elsewhere): start over.
            session = null
            showConfirm = null
        }
        homeOutAtMs = null
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        // Set before launching so a second press in the same frame sees Downloading.
        _state.value = UpdateState.Downloading(0)
        scope.launch {
            when (val r = net.download(release, cacheDir) { _state.value = UpdateState.Downloading(it) }) {
                is DownloadResult.Ok -> commit(r.file)
                DownloadResult.Mismatch -> _state.value = UpdateState.Mismatch
                DownloadResult.Failed -> _state.value = UpdateState.DownloadFailed
            }
        }
    }

    private suspend fun commit(file: File) {
        apkFile = file
        closeCc()
        // Backup for a cancel/failure result that arrives while no host is collecting reopenRequests.
        leftAtMs = clock()
        try {
            val id = withContext(Dispatchers.IO) { installer.createSession(file) }
            session = id
            // Before the commit, so a quick result can't be overwritten by a late "Installing".
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { installer.commit(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            abandonSession()
            finishFailed(e.message ?: "unknown error")
        }
    }

    fun onAllowPressed() {
        leftAtMs = clock()
        settingsOpenedAtMs = leftAtMs
        closeCc()
        installer.openPermissionSettings()
    }

    /**
     * PENDING_USER_ACTION from [InstallStatusReceiver]: [show] starts the confirm prompt (null if Android sent none).
     * Only for the current session; it is kept for Install after a Home-out.
     */
    fun onConfirmNeeded(sessionId: Int, show: (() -> Unit)?) {
        if (sessionId != session) return
        if (show == null) {
            abandonSession()
            finishFailed("no install prompt")
            return
        }
        showConfirm = show
        launchConfirm(show)
    }

    private fun launchConfirm(show: () -> Unit) {
        try {
            show()
        } catch (e: Exception) {
            abandonSession()
            finishFailed(e.message ?: "couldn't show the install prompt")
        }
    }

    /**
     * Final result from [InstallStatusReceiver]. Only the current session counts: a late result from an earlier
     * attempt must not touch a newer one.
     */
    fun onInstallStatus(sessionId: Int, status: Int, message: String?) {
        if (sessionId != session || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        // A cancelled session stays open in Android (its prompt could be confirmed later); close it for good. A failed
        // one is already gone.
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) abandonSession() else session = null
        showConfirm = null
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                leftAtMs = null
                deleteApk()
                _state.value = UpdateState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                deleteApk()
                _state.value = UpdateState.Cancelled
                val homeOutAt = homeOutAtMs
                homeOutAtMs = null
                // After a Home-out the user may be in another app by now: reopen only right after it, else stay quiet.
                if (homeOutAt != null && clock() - homeOutAt > LATE_CANCEL_REOPEN_MS) leftAtMs = null
                else requestReopen()
            }
            else -> finishFailed(message ?: "unknown error")
        }
    }

    private fun finishFailed(reason: String) {
        deleteApk()
        _state.value = UpdateState.InstallFailed(reason)
        requestReopen()
    }

    /** Emits live if a host is listening (and clears the backup flag so resume doesn't reopen twice); else leaves the flag for the next resume. */
    private fun requestReopen() {
        if (_reopen.subscriptionCount.value > 0) {
            leftAtMs = null
            _reopen.tryEmit(Unit)
        } else {
            leftAtMs = clock()
        }
    }

    companion object {
        /** How long a resumed Home waits for the prompt's own result before treating the prompt as left with Home. */
        internal const val PROMPT_RESULT_GRACE_MS = 2_000L
        /**
         * Resumes this soon after "Allow installs first" are home passing through on its way behind Settings, not the
         * user coming back (nobody leaves Settings that fast).
         */
        internal const val SETTINGS_SETTLE_MS = 1_500L
        /** How long after leaving the Update page a Home resume still reopens it. */
        internal const val REOPEN_WINDOW_MS = 5 * 60_000L
        /** How long after a Home-out reset a late Cancel from that prompt still reopens the page. */
        internal const val LATE_CANCEL_REOPEN_MS = 10_000L
    }

    /** Only for a session that is over (cancelled, failed to commit, or no prompt); never from a timer. */
    private fun abandonSession() {
        session?.let { id -> runCatching { installer.abandon(id) } }
        session = null
        showConfirm = null
    }

    private fun deleteApk() {
        apkFile?.delete()
        apkFile = null
    }
}

/** Real installer: the per-app "Install unknown apps" switch plus a PackageInstaller session. */
class AndroidInstaller(private val app: Application) : InstallerPort {
    override fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun openPermissionSettings() {
        val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some TV firmwares have no per-app screen; the general Settings app is the fallback.
        val intent = if (perApp.resolveActivity(app.packageManager) != null) perApp
        else Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // NemuiTV home goes in front first (the panel may have been over another app), so Back from Settings lands
        // on home, whose resume reopens the Update page.
        val home = Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivities(arrayOf(home, intent))
    }

    override fun abandonOwnSessions() {
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    override fun createSession(apk: File): Int {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Always Android's confirm prompt, even where the system could skip it for an app updating itself.
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("NemuiTV.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    override fun abandon(sessionId: Int) = app.packageManager.packageInstaller.abandonSession(sessionId)

    override fun isSessionOpen(sessionId: Int): Boolean =
        app.packageManager.packageInstaller.getSessionInfo(sessionId) != null

    override fun commit(sessionId: Int) {
        app.packageManager.packageInstaller.openSession(sessionId).use { session ->
            val callback = Intent(app, InstallStatusReceiver::class.java).setPackage(app.packageName)
            // Mutable: the system fills in the status extras.
            val pending = PendingIntent.getBroadcast(
                app, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

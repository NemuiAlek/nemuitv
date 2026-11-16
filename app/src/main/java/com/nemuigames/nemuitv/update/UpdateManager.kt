package com.nemuigames.nemuitv.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
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
    fun commit(apk: File)
}

/**
 * Finds newer releases and runs the install. Lives in NemuiCore so a download keeps going and the
 * state survives while the Control Center is closed (it must be hidden before Settings or the
 * install prompt, since the overlay draws above system screens).
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val net: UpdateNet,
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
    private var leftFromUpdatePage = false
    private var apkFile: File? = null

    /** First check after a short delay, then every six hours while the app runs. */
    fun start() {
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
        if (leftFromUpdatePage) {
            leftFromUpdatePage = false
            if (_state.value == UpdateState.NeedsPermission && installer.canInstall()) {
                _state.value = UpdateState.Idle
            }
            _reopen.tryEmit(Unit)
        }
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
        try {
            withContext(Dispatchers.IO) { installer.commit(file) }
            _state.value = UpdateState.Installing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishFailed(e.message ?: "unknown error")
        }
    }

    fun onAllowPressed() {
        leftFromUpdatePage = true
        closeCc()
        installer.openPermissionSettings()
    }

    /** Result from [InstallStatusReceiver]; PENDING_USER_ACTION is handled there (it launches the prompt). */
    fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> {
                deleteApk()
                _state.value = UpdateState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                deleteApk()
                _state.value = UpdateState.Cancelled
                _reopen.tryEmit(Unit)
            }
            else -> finishFailed(message ?: "unknown error")
        }
    }

    private fun finishFailed(reason: String) {
        deleteApk()
        _state.value = UpdateState.InstallFailed(reason)
        _reopen.tryEmit(Unit)
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
        app.startActivity(intent)
    }

    override fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("NemuiTV.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = Intent(app, InstallStatusReceiver::class.java).setPackage(app.packageName)
            // Mutable: the system fills in the status extras.
            val pending = PendingIntent.getBroadcast(
                app, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

package com.nemuigames.nemuitv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.nemuigames.nemuitv.core.core

/** Receives the PackageInstaller session result (confirm prompt request, success, cancel, failure). */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            return
        }
        context.core.update.onInstallStatus(
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1), status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }
}

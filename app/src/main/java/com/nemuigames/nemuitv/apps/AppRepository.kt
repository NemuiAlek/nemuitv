package com.nemuigames.nemuitv.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lists installed apps, keeps the list fresh on install/uninstall, and launches them. */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val pm: PackageManager = this.context.packageManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    // Declared before `init` on purpose: init calls reload(), which clears these.
    // Decoded artwork per package, so tiles don't hit PackageManager + re-decode on every recomposition.
    private object NoBanner
    private val bannerCache = ConcurrentHashMap<String, Any>()
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = reload()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        reload()
    }

    /** Stop listening for installs/uninstalls (called when the home screen's ViewModel goes away). */
    fun close() = runCatching { context.unregisterReceiver(receiver) }

    fun reload() {
        bannerCache.clear(); iconCache.clear() // an update can change an app's artwork
        scope.launch(Dispatchers.IO) {
            val list = mergeLaunchables(
                leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER, isLeanback = true),
                launcher = query(Intent.CATEGORY_LAUNCHER, isLeanback = false),
                selfPackage = context.packageName,
            )
            _apps.value = list
            // Decode every app's artwork now, in the background, so the first tray open doesn't stall
            // (measured: a ~600 ms freeze on the Streamer when it was decoded on first draw).
            list.forEach { runCatching { bannerBitmap(it); iconBitmap(it) } }
        }
    }

    private fun query(category: String, isLeanback: Boolean): List<AppInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).map {
            AppInfo(
                packageName = it.activityInfo.packageName,
                label = it.loadLabel(pm).toString(),
                activityName = it.activityInfo.name,
                isLeanback = isLeanback,
                isSystem = it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }

    fun launch(app: AppInfo) {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: return
        start(app, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** startActivity, but a missing / broken handler shows a short message instead of crashing the launcher. */
    private fun start(app: AppInfo, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android's own page for the app: Force stop (works even on the app you just left), Clear cache, Uninstall. */
    fun openAppInfo(app: AppInfo) {
        start(
            app,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Asks Android to uninstall the app (its own confirm screen; the package receiver refreshes the list afterwards). */
    fun uninstall(app: AppInfo) {
        start(
            app,
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The app's 16:9 TV banner as a bitmap (cached), or null if it doesn't have one. */
    fun bannerBitmap(app: AppInfo): ImageBitmap? =
        bannerCache.getOrPut(app.packageName) { banner(app)?.toBitmap(320, 180)?.asImageBitmap() ?: NoBanner } as? ImageBitmap

    /** The app's icon as a bitmap (cached). */
    fun iconBitmap(app: AppInfo): ImageBitmap =
        iconCache.getOrPut(app.packageName) { icon(app).toBitmap(128, 128).asImageBitmap() }

    /** The app's 16:9 TV banner, or null if it doesn't have one. */
    private fun banner(app: AppInfo): Drawable? = try {
        pm.getActivityBanner(ComponentName(app.packageName, app.activityName))
            ?: pm.getApplicationBanner(app.packageName)
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun icon(app: AppInfo): Drawable = try {
        pm.getActivityIcon(ComponentName(app.packageName, app.activityName))
    } catch (_: PackageManager.NameNotFoundException) { pm.defaultActivityIcon }
}

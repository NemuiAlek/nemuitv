package com.nemuigames.nemuitv.apps

/** One launchable app. Icons/banners are loaded separately (see AppRepository) so this stays a plain value. */
data class AppInfo(val packageName: String, val label: String, val activityName: String, val isLeanback: Boolean, val isSystem: Boolean)

/** One entry per package (TV/leanback activity wins), NemuiTV itself excluded, sorted by label ignoring case. */
fun mergeLaunchables(leanback: List<AppInfo>, launcher: List<AppInfo>, selfPackage: String): List<AppInfo> {
    val byPackage = LinkedHashMap<String, AppInfo>()
    (leanback + launcher).forEach { byPackage.putIfAbsent(it.packageName, it) }
    return byPackage.values
        .filter { it.packageName != selfPackage }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/** Live search for the app tray: trimmed, case-insensitive substring match on the label. Blank = everything. */
fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter { it.label.contains(q, ignoreCase = true) }
}

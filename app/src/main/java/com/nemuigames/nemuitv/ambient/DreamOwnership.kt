package com.nemuigames.nemuitv.ambient

/**
 * Whether Settings.Secure screensaver_components ([components], one or more entries separated by ',' or ':') names
 * this app's own [AmbientDream], in full or short spelling. Matching the class name alone would also claim an
 * AmbientDream from another package (an older build installed side by side), so the package must match too.
 */
fun isOurDream(components: String?, pkg: String): Boolean {
    if (components.isNullOrBlank() || components == "null") return false
    val full = "$pkg/$pkg.ambient.AmbientDream"
    val short = "$pkg/.ambient.AmbientDream"
    return components.split(',', ':').map(String::trim).any { it == full || it == short }
}

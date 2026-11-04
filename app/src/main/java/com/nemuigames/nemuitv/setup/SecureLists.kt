package com.nemuigames.nemuitv.setup

/**
 * Edits the colon-separated component lists Android keeps in Settings.Secure (`enabled_accessibility_services`,
 * `enabled_notification_listeners`). Android may store a component in its long form (`pkg/pkg.Cls`) or short form
 * (`pkg/.Cls`), so both spellings count as "ours". Other entries are never touched.
 */
object SecureLists {
    private fun entries(list: String?): List<String> =
        if (list.isNullOrBlank() || list == "null") emptyList() else list.split(':').filter { it.isNotBlank() }

    fun contains(list: String?, component: String, shortForm: String): Boolean =
        entries(list).any { it == component || it == shortForm }

    fun add(list: String?, component: String, shortForm: String): String {
        val current = entries(list)
        if (current.any { it == component || it == shortForm }) return current.joinToString(":")
        return (current + component).joinToString(":")
    }

    fun remove(list: String?, component: String, shortForm: String): String =
        entries(list).filter { it != component && it != shortForm }.joinToString(":")
}

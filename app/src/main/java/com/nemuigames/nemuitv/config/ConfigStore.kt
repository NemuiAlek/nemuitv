package com.nemuigames.nemuitv.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Watches `<dir>/config.json` (pushed from the Mac with ADB).
 * Call [loadIfChanged] whenever the launcher comes to the front; it only re-reads when the file changed.
 */
class ConfigStore(private val dir: File) {
    private val _state = MutableStateFlow<ConfigResult>(ConfigResult.Missing)
    val state: StateFlow<ConfigResult> = _state.asStateFlow()

    // (lastModified, length) of the file last time we looked; NEVER_LOADED forces the first read.
    private var lastSeen: Pair<Long, Long>? = NEVER_LOADED

    /** Returns true if the file changed (or appeared/disappeared) and [state] was updated. */
    fun loadIfChanged(): Boolean {
        val file = File(dir, FILE_NAME)
        val seen = if (file.isFile) file.lastModified() to file.length() else null
        if (seen == lastSeen) return false
        lastSeen = seen
        _state.value = parseConfig(if (seen == null) null else file.readText())
        return true
    }

    companion object {
        const val FILE_NAME = "config.json"
        private val NEVER_LOADED = -1L to -1L
    }
}
